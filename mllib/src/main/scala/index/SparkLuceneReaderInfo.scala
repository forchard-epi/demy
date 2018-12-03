package demy.mllib.index;

import org.apache.lucene.search.{IndexSearcher, TermQuery, BooleanQuery, FuzzyQuery}
import org.apache.lucene.store.NIOFSDirectory
import org.apache.lucene.index.{DirectoryReader, Term}
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.queries.function.FunctionQuery
import org.apache.lucene.queries.function.valuesource.DoubleFieldSource
import org.apache.lucene.search.BoostQuery
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.apache.lucene.document.{Document, TextField, StringField, IntPoint, BinaryPoint, LongPoint, DoublePoint, FloatPoint, Field, StoredField}
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import java.io.{ObjectInputStream,ByteArrayInputStream}
import scala.collection.JavaConverters._

case class SparkLuceneReaderInfo(searcher:IndexSearcher, tmpIndex:NIOFSDirectory, reader:DirectoryReader, usePopularity:Boolean = false) {
    def search(query:String, maxHits:Int, filter:Row = Row.empty, outFields:Seq[StructField]=Seq[StructField](), maxLevDistance:Int=2 , minScore:Double=0.0, boostAcronyms:Boolean=false) = {
        val terms = (if(query == null) "" else  query).replaceAll("[^\\p{L}]+", ",").split(",").filter(s => s.length>0)
        val qb = new BooleanQuery.Builder()
        val fuzzyb = new BooleanQuery.Builder()
        if(maxLevDistance>0) {
            terms.foreach(s => {
                var allLetterUppercase = Range(0, s.length).forall(ind => s(ind).isUpper)

                // if term is only in Uppercase -> double term: "TX" -> "TXTX" (ensures that term is not neglected due to too less letters)
                if (allLetterUppercase) {
                    fuzzyb.add(new BoostQuery(new TermQuery(new Term("_text_", s+s)), 15.00F), Occur.SHOULD) // High boosting factor to find doubles
                    fuzzyb.add(new BoostQuery(new TermQuery(new Term("_text_", s.toLowerCase)), 4.00F), Occur.SHOULD)
                } else {
                    fuzzyb.add(new FuzzyQuery(new Term("_text_", s.toLowerCase), 1, maxLevDistance), Occur.SHOULD)
                    fuzzyb.add(new BoostQuery(new TermQuery(new Term("_text_", s.toLowerCase)), 4.00F), Occur.SHOULD) 
                }
            })
        }
        else {
            terms.foreach(s => {
                var allLetterUppercase = Range(0, s.length).forall(ind => s(ind).isUpper)

                // if term is only in Uppercase -> double term: "TX" -> "TXTX" (ensures that term is not neglected due to too less letters)
                if (allLetterUppercase) {
                    val bst = new BoostQuery(new TermQuery(new Term("_text_", s+s)), 4.00F)  // Boosting factor of 4.0 for exact match
                    fuzzyb.add(bst, Occur.SHOULD)
                } else { 
                    val bst = new BoostQuery(new TermQuery(new Term("_text_", s.toLowerCase)), 4.00F) // boosting factor of 4.0 for exact match
                    fuzzyb.add(bst, Occur.SHOULD)
                }
            })
        }

        qb.add(fuzzyb.build, Occur.MUST)
        if(filter.schema != null) {
           filter.schema.fields.zipWithIndex.foreach(p => p match { case (field, i) => 
              if(!filter.isNullAt(i)) field.dataType match { 
              case dt:StringType => qb.add(new TermQuery(new Term(field.name, filter.getAs[String](i))), Occur.MUST)
              case dt:IntegerType => qb.add(IntPoint.newExactQuery("_point_"+field.name, filter.getAs[Int](i)), Occur.MUST)
              case dt:BooleanType => qb.add(BinaryPoint.newExactQuery("_point_"+field.name, Array(filter.getAs[Boolean](i) match {case true => 1.toByte case _ => 0.toByte})), Occur.MUST)
              case dt:LongType => qb.add(LongPoint.newExactQuery("_point_"+field.name, filter.getAs[Long](i)), Occur.MUST)
              case dt:FloatType => qb.add(FloatPoint.newExactQuery("_point_"+field.name, filter.getAs[Float](i)), Occur.MUST)
              case dt:DoubleType => qb.add(DoublePoint.newExactQuery("_point_"+field.name, filter.getAs[Double](i)), Occur.MUST)
              case dt => throw new Exception(s"Spark type {$dt.typeName} cannot be used as a filter since it has not been indexed")
           }})
        }
        val q = if(usePopularity) {
                   val pop = new FunctionQuery(new DoubleFieldSource("_pop_"));
                   new org.apache.lucene.queries.CustomScoreQuery(qb.build, pop);
                } else qb.build
        
        //query.replaceAll("[^\\p{L}\\-]+", ",").split(",").foreach(s => qb.add(new TermQuery(new Term("text", s)), Occur.SHOULD))
        val outSchema = StructType(outFields.toList :+ StructField("_score_", FloatType))
        val docs = searcher.search(q, maxHits);
        val hits = docs.scoreDocs;
        if(query!=null) {
          hits.flatMap(hit => {
            if(hit.score < minScore) None
            else {
              val doc = searcher.doc(hit.doc)
              Some(new GenericRowWithSchema(
                values = outFields.toArray.map(field => {
                  val lucField = doc.getField(field.name)
                  if(field.name == null || lucField == null) null
                  else
                    field.dataType match {
                  case dt:StringType => lucField.stringValue 
                  case dt:IntegerType => lucField.numericValue().intValue()
                  case dt:BooleanType => lucField.binaryValue().bytes(0) == 1.toByte
                  case dt:LongType =>  lucField.numericValue().longValue()
                  case dt:FloatType => lucField.numericValue().floatValue()
                  case dt:DoubleType => lucField.numericValue().doubleValue()
                  case dt => {
                    var obj:Any = null
                    val serData= lucField.binaryValue().bytes;
                    if (serData!=null) {
                       val in=new ObjectInputStream(new ByteArrayInputStream(serData))
                       obj = in.readObject()
                       in.close()
                    }
                    obj
                  }
                  }}) ++ Array(hit.score)
                ,schema = outSchema))
            }
        })
      } else Array[GenericRowWithSchema]()
    }
    def deleteRecurse(path:String) {
        if(path!=null && path.length>1 && path.startsWith("/")) {
            val f = java.nio.file.Paths.get(path).toFile
            if(!f.isDirectory)
              f.delete
            else {
                f.listFiles.filter(ff => ff.toString.size > path.size).foreach(s => this.deleteRecurse(s.toString))
                f.delete
            }
        }
    }
    def close(deleteLocal:Boolean = false) {
        val dir = tmpIndex.getDirectory().toString
        if(new java.io.File(dir).exists()) {
            if(deleteLocal) 
                this.deleteRecurse(dir)
        }
        tmpIndex.close
        reader.close
    }
}
