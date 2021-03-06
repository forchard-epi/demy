package demy.mllib.topic

import demy.mllib.index.VectorIndex
import demy.mllib.linalg.implicits._
import demy.util.{log => l}
import demy.mllib.tuning.BinaryOptimalEvaluator
import demy.mllib.evaluation.BinaryMetrics
import org.apache.spark.ml.linalg.{Vector => MLVector, Vectors}
import org.apache.spark.sql.{SparkSession}
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap}
import org.apache.spark.ml.classification.{LinearSVC, LinearSVCModel}
import scala.{Iterator => It}
import java.sql.Timestamp
import scala.util.Random

/** A ClassifierNode to train a classifier from annotations
 *
 * @param points The word vectors for the annotations (Training set)
 * @param params @tparam NodeParams The parameters of the node
 * @param children @tparam ArrayBuffer[Node] Array of children nodes for this Classifier node
 * @param models @tparam HashMap[Int, WrappedClassifier] Map of classes to their Classifiers
 */
case class ClassifierNode (
  points:ArrayBuffer[MLVector] = ArrayBuffer[MLVector]() // fastText vector
  , params:NodeParams
  , children: ArrayBuffer[Node] = ArrayBuffer[Node]()
) extends Node {
  val models = HashMap[Int, WrappedClassifier]()
  val windowSize = this.params.windowSize.getOrElse(2)
  def encodeExtras(encoder:EncodedNode) {
    encoder.serialized += (("models", serialize(models.map(p => (p._1, p._2.model)))))
  }
  def prettyPrintExtras(level:Int = 0, buffer:ArrayBuffer[String]=ArrayBuffer[String](), stopLevel:Int = -1):ArrayBuffer[String] = {
    buffer
  }
  /** Create ClassifierTagSource from TagSource */
  def toTag(id:Int):TagSource = ClassifierTagSource(
    id = this.params.tagId.getOrElse(id)
    , operation = TagOperation.create
    , timestamp = Some(new Timestamp(System.currentTimeMillis()))
    , name = Some(this.params.name)
    , color = this.params.color
    , inTag = Some(this.params.strLinks.keys.map(_.toInt).toSet.toSeq match {case Seq(inTag) => inTag case _ => throw new Exception("Cannot transforme multi in classifier to Tag")})
    , outTags = Some(this.params.strLinks.values.flatMap(e => e).toSet)
    , oFilterMode = Some(this.params.filterMode)
    , oFilterValue = Some(this.params.filterValue.toSet)
    , windowSize = Some(this.windowSize)
  )

  /** Transform updates the facts and scores
   *
   * @param facts @tparam HashMap[Int, HashMap[Int, Int]] Mapping for each class of vectors a HashMap with the indices of vectors/tokens having the class
   * @param scores @tparam HashMap[Int, Double] Maps for each class the global score of all vectors having this class
   * @param vectors @tparam Seq[MLVector] List of word vectors
   * @param tokens @tparam Seq[String] List of tokens
   * @param parent @tparam Option[Node] Parent node
   * @param cGenerator @tparam Iterator[Int]
   * @param fit @tparam Boolean
  */
  def transform(facts:HashMap[Int, HashMap[Int, Int]]
      , scores:HashMap[Int, Double]
      , vectors:Seq[MLVector]
      , tokens:Seq[String]
      , parent:Option[Node]
      , cGenerator:Iterator[Int]
      , fit:Boolean) {

    var setScores = (idxs:Iterator[Int], oClass:Int, score:Double ) => {
      var first:Option[Int] = None
      if (score > 0.5) {
        for(i <- idxs) {
          first = first.orElse(Some(i))
          facts.get(oClass) match {
            case Some(f) => f(i) = first.get
            case None => facts(oClass) = HashMap(i -> first.get)
          }
        }
      }

      scores.get(oClass) match {
        case Some(s) => scores(oClass) = if(s > score) s else score
        case None => scores(oClass) = score
      }
    }
    //println(s"this.linkPairs : ${this.linkPairs}")
    for((inClass, outClass) <- this.linkPairs) {
      //println(s"\tinClass: $inClass, outClass: $outClass")
      val idx = facts(inClass).iterator.map{case (iIn, _) => iIn}.toSeq.sortWith(_ < _)
      var allVector:Option[MLVector] = None
      var bestDocScore = 0.0
      var bestITo = -1
      var bestIndScore = 0.0
      var bestPosScore = 0.0
      var bestFrom = -1
      var bestTo = -1
      var sum:Option[MLVector] = None
      var bestSum:Option[MLVector] = None

      for{(iIn, iPos) <- idx.iterator.zipWithIndex} {
        //println(s"\t\tiIn: ${iIn}, iPos: ${iPos}, bestITo: ${bestITo}")
        allVector = Some(allVector.map(v => v.sum(vectors(iIn))).getOrElse(vectors(iIn))) // sum of all vectors

        if (windowSize != -1) {
          Some(this.score(outClass, vectors(iIn))) // each vector validated on classifiers
            .map{score =>
              setScores(It(iIn), outClass, score) // updates facts and scores
              // if(score > 0.5) setScores(It(iIn), outClass, score)
              if(score > bestIndScore) bestIndScore = score
            } //always setting if current vector is classifier in the outClass

          if(iIn > bestITo) {//start expanding the right side right window
            bestIndScore = 0.0
            bestPosScore = 0.0
            bestFrom = iPos
            bestTo = iPos
            sum = None
            bestSum = None
            It.range(iPos, idx.size)
              .map(i => {
                sum = sum.map(v =>v.sum(vectors(idx(i)))).orElse(Some(vectors(idx(i))));
                (sum.get, i)
                })
              .map{case (vSum, i) => (i, this.score(outClass, vSum), vSum)} // calls classifiers
              .map{case (i, score, vSum) =>
                if(score > bestPosScore) {
                  bestPosScore = score
                  bestTo = i
                  bestITo = idx(i)
                  bestSum = Some(vSum)
                }
                (i, score) // score of expanded window
              }.takeWhile{case (i, score) => i < iPos + windowSize || i < bestTo + windowSize} //
              .size // just to execute
              sum = bestSum
          } else { //contracting the left side window
            sum = sum.map(v => v.minus(vectors(idx(iPos -1))))
            sum
              .map{v => this.score(outClass, v)}
              .map{score =>
                if(score > bestPosScore) {
                  bestPosScore = score
                  bestFrom = iPos
                }
              }
          }
          if(iIn == bestITo) {
            if(bestPosScore > bestIndScore) {
              setScores(It.range(bestFrom, bestTo + 1).map(i => idx(i)), outClass, bestPosScore)
            }
            if(bestPosScore > bestDocScore) bestDocScore = bestPosScore
            if(bestIndScore > bestDocScore) bestDocScore = bestIndScore
          }
        }
      }

      if (windowSize > 0 || windowSize == -1)
        allVector
          .map(v => this.score(outClass, v))
          .map{allScore =>
            if(allScore > bestDocScore) {
              //println(s"\tallScore: $allScore, outClass: $outClass, bestDocScore:$bestDocScore")
              setScores(idx.iterator, outClass, allScore)
            }
          }
    }
  }

  def score(forClass:Int, vector:MLVector) = {
    this.models(forClass).score(vector)
  }

  def getPoints(nodes:Seq[Node], positive:Boolean, negative:Boolean):Iterator[(MLVector, Boolean)] =
    if(nodes.isEmpty) Iterator[(MLVector, Boolean)]()
    else (
      nodes
        .iterator.flatMap{case n:ClassifierNode => Some(n)
                          case _ => None}
        .flatMap(n => {
          n.inRel.values.iterator
            .flatMap(points => points.iterator
              .flatMap{case ((i, from), inRel) => {
                if(inRel && positive && n.windowSize == this.windowSize) Some(n.points(i), true)
                else if (!inRel && negative && n.windowSize == this.windowSize) Some(n.points(i), false)
                else None
              }
         })
       }
     ) ++ getPoints(nodes.flatMap(n => {
                  n.children.flatMap{case c:ClassifierNode => Some(c)
                                     case _ => None}
                                  }), positive, negative)
    )

    def getDescendantClasses(nodes:Seq[Node]):Iterator[Int] = (
      if(nodes.isEmpty) Iterator[Int]()
      else (
        nodes
          .iterator.flatMap{case n:ClassifierNode => Some(n)
                            case _ => None}
          .flatMap(n => {
                  if(n.windowSize == this.windowSize) n.outClasses.iterator
                  else Iterator[Int]()
                }
           ) ++ getDescendantClasses(nodes.flatMap(n => {
                    n.children.flatMap{case c:ClassifierNode => Some(c)
                                       case _ => None}
                                    }))
      ))


  /** Returns fitted Classifier Node
   *
   * @param spark @tparam SparkSession
   * @param excludedNodes @tparam Seq[Node]
   * @return Fitted Classifier Node
  */
  def fit(spark:SparkSession, excludedNodes:Seq[Node]) = {
    l.msg(s"Start classifier fitting models for windowSize ${this.windowSize}")
    this.models.clear
    val thisPoints = this.points.filter(_ != null)
    val thisClasses = (c:Int) =>
      (for(i<-It.range(0, this.points.size))
        yield(this.rel(c).get(i) match {
          case Some(from) if this.inRel(c)((i, from)) => c
          case _ => -1
        })
      ).toSeq
       .zip(this.points)
       .flatMap{case(c, p) => if(p == null) None else Some(c)}


    val otherPointsOut = getPoints(excludedNodes, true, false).map{case (v, inRel) => (v)}.toSeq
    val otherChildrenPoints = getPoints(this.children, true, false).toSeq
    println("name:"+this.params.name)
    println("size annotations: "+this.params.annotations.size)
    println("Positive annotations:"+this.params.annotations.filter(a => a.inRel).size)
    println("negative annotations:"+this.params.annotations.filter(a => !a.inRel).size)

    for(c <- this.outClasses) {
      this.models(c) = WrappedClassifier(
          forClass = c
          , points = thisPoints ++ otherPointsOut ++ otherChildrenPoints.map(_._1)
          , pClasses = thisClasses(c) ++ otherPointsOut.map(_ => -1) ++ otherChildrenPoints.map{case (v, inRel) => if(inRel) c else -1}
          , spark = spark)
    }
    l.msg("Classifier fit")
    this
  }


  /** Returns metrics for classifier performance
   *
   * @param index @tparam Option[VectorIndex]
   * @param allAnnotations @tparam Seq[AnnotationSource] List of annotations
   * @param spark @tparam SparkSession
   * @return Tuple (metrics, node name, node tag id, classes, current time stamp, postive annotations, negative annotations)
  */
  def evaluateMetrics(index:Option[VectorIndex], allAnnotations:Seq[AnnotationSource], spark:SparkSession, excludedNodes:Seq[Node] = Seq[Node]()) : ArrayBuffer[PerformanceReport] = {
      import spark.implicits._
      val r = new Random(0)
      val posClasses = getDescendantClasses(Seq(this)).toSet // get all classes for current and children node
      val negClasses = getDescendantClasses(excludedNodes).toSet // get all classes for brothers
      val currentAnnotationsPositive = allAnnotations.filter(a => posClasses(a.tag) && a.inRel)
      val currentAnnotationsNegative = allAnnotations.filter(a => (this.outClasses(a.tag) && !a.inRel) || (negClasses(a.tag)  && a.inRel)).map(a => a.setInRel(false))
      // println("Class: "+this.outClasses)
      // println("Number positive annotations:"+allAnnotations.filter(a => (this.outClasses(a.tag) && a.inRel)).size)
      // println("Number negative annotations:"+allAnnotations.filter(a => (this.outClasses(a.tag) && !a.inRel)).size)
      val trainPos = if (currentAnnotationsPositive.length == 1) currentAnnotationsPositive.toSet
                     else {
                       // make sure that at least one positive training annotation from the current class (!) is in trainPos, otherwise the current class is not found in trainNode.rel
                       val annotCurrentClass = currentAnnotationsPositive.filter(a => a.tag == this.outClasses.toList(0))(0) // take an annotation of the current Tag and make sure it is in the training data
                       val currentAnnotationsPositiveWithoutOne = currentAnnotationsPositive.filter(a => a != annotCurrentClass)
                       r.shuffle(currentAnnotationsPositiveWithoutOne).take((currentAnnotationsPositiveWithoutOne.length*0.8).toInt).toSet ++ Set(annotCurrentClass)
                     }
      val trainNeg = r.shuffle(currentAnnotationsNegative).take((currentAnnotationsNegative.length*0.7).toInt).toSet
      val train = r.shuffle(trainPos ++ trainNeg)
      val test = (r.shuffle(currentAnnotationsPositive.toSet -- trainPos) ++ (currentAnnotationsNegative.toSet -- trainNeg)).toList

      val newParams = this.params.cloneWith(None, true) match {
        case Some(value) => value
        case None => throw new Exception("ERROR: cloneWith current classifier node returned None in function evaluateMetrics")
      }
      newParams.annotations ++= train.map(_.toAnnotation)
      val trainNode = newParams.toNode(vectorIndex = index).asInstanceOf[ClassifierNode]

      val trainClasses = (c:Int) =>
        (for(i<-It.range(0, trainNode.points.size))
          yield(
            trainNode.rel(c).get(i) match {
                case Some(from) => if (trainNode.inRel(c)((i, from))) c else -1
                case _ if posClasses.exists( pc => !trainNode.rel.get(pc).isEmpty && !trainNode.rel(pc).get(i).isEmpty) => c // translate original positiv classes to current class
                case _ => -1
           })
        ).toSeq
         .zip(trainNode.points)
         .flatMap{case(c, p) => if(p == null) None else Some(c)}

      val trainPoints = trainNode.points.filter( _!= null)
      for(c <- this.outClasses) {

        trainNode.models(c) = WrappedClassifier(
            forClass = c
            , points = trainPoints
            , pClasses = trainClasses(c)
            , spark = spark)

      }
      var output:ArrayBuffer[PerformanceReport] = ArrayBuffer.empty[PerformanceReport]
      // println("test pos:"+test.filter(a => a.inRel == true).size)
      // println("test neg:"+test.filter(a => a.inRel == false).size)
      // println("NODE: "+this.params.name)
      for(c <- this.outClasses) {
        val testDF = test.map { a =>
          val tokens = a.tokens
          val vectors:Seq[MLVector] = index match {
            case Some(wordVectorIndex) => wordVectorIndex(tokens) match {
              case vectorsMap => tokens.map( (token:String) => vectorsMap.get(token).getOrElse(null))
            }
            case None => throw new Exception("Provided vectorIndex is None!")
          }
          val facts:HashMap[Int,HashMap[Int,Int]] = HashMap((this.inMap(c), HashMap(It.range(0, tokens.size).filter(i => vectors(i)!=null).map(i => i -> i).toSeq :_* )))
          val scores = HashMap[Int, Double]()
          trainNode.transform(facts = facts
              , scores = scores
              , vectors = vectors
              , tokens = tokens
              , parent = None
              , cGenerator = Iterator[Int]()
              , fit = false)
          (if(a.inRel) 1.0 else 0.0, scores.get(c).getOrElse(0.0))
        }.toDS()
         .withColumnRenamed("_1", "label")
         .withColumnRenamed("_2", "score")

        println("\nThis Node: "+this.params.name)
        println("Positive training samples:"+(train.filter(a => a.inRel == true)).size)
        println("Negative training samples:"+(train.filter(a => a.inRel == false)).size)
        println("Positive test samples:"+(test.filter(a => a.inRel == true)).size)
        println("Negative test samples:"+(test.filter(a => a.inRel == false)).size)

        val evaluator = new BinaryOptimalEvaluator().fit(testDF)
        val metrics = evaluator.metrics
        this.params.metrics =
          this.params.metrics ++ Map(s"prec${if(this.outClasses.size > 1) s"_$c" else ""}" ->  metrics.precision.getOrElse(0.0),
                                  s"recall${if(this.outClasses.size > 1) s"_$c" else ""}" ->  metrics.recall.getOrElse(0.0),
                                  s"f1${if(this.outClasses.size > 1) s"_$c" else ""}" ->  metrics.f1Score.getOrElse(0.0),
                                  s"AUC${if(this.outClasses.size > 1) s"_$c" else ""}" ->  metrics.areaUnderROC.getOrElse(0.0),
                                  s"accuracy${if(this.outClasses.size > 1) s"_$c" else ""}" ->  metrics.accuracy.getOrElse(0.0),
                                  s"pValue${if(this.outClasses.size > 1) s"_$c" else ""}" ->  metrics.pValue.getOrElse(0.0),
                                  s"threshold${if(this.outClasses.size > 1) s"_$c" else ""}" -> metrics.threshold.getOrElse(0.0),
                                  s"positiveAnnotations${if(this.outClasses.size > 1) s"_$c" else ""}" -> ((train++test).filter(a => a.inRel == true)).size.toDouble,
                                  s"negativeAnnotations${if(this.outClasses.size > 1) s"_$c" else ""}" -> ((train++test).filter(a => a.inRel == false)).size.toDouble,
                                  s"tp${if(this.outClasses.size > 1) s"_$c" else ""}" -> metrics.tp.getOrElse(0).toDouble,
                                  s"fp${if(this.outClasses.size > 1) s"_$c" else ""}" -> metrics.fp.getOrElse(0).toDouble,
                                  s"tn${if(this.outClasses.size > 1) s"_$c" else ""}" -> metrics.tn.getOrElse(0).toDouble,
                                  s"fn${if(this.outClasses.size > 1) s"_$c" else ""}" -> metrics.fn.getOrElse(0).toDouble
                                )
        this.params.rocCurve ++ Map(s"rocCurve${if(this.outClasses.size > 1) s"_$c" else ""}" -> metrics.rocCurve)

        output += PerformanceReport(metrics.threshold
                                  , metrics.precision
                                  , metrics.recall
                                  , metrics.f1Score
                                  , metrics.areaUnderROC
                                  , metrics.rocCurve
                                  , metrics.accuracy
                                  , metrics.pValue
                                  , this.params.name
                                  , this.params.tagId
                                  , c
                                  , new Timestamp(System.currentTimeMillis())
                                  , allAnnotations.filter(a => (this.outClasses(a.tag) && a.inRel)).size
                                  , allAnnotations.filter(a => (this.outClasses(a.tag) && !a.inRel)).size
                                  , ((train++test).filter(a => a.inRel == true)).size
                                  , ((train++test).filter(a => a.inRel == false)).size
                                  , metrics.tp
                                  , metrics.fp
                                  , metrics.tn
                                  , metrics.fn
                                )
      }
      output
  }
  def mergeWith(that:Node, cGenerator:Iterator[Int], fit:Boolean):this.type = {
    this.params.hits = this.params.hits + that.params.hits
//    TODO: merge externalClassesFreq for this that
    It.range(0, this.children.size).foreach(i => this.children(i).mergeWith(that.children(i), cGenerator, fit))
    this
  }

  def updateParamsExtras {}
  def resetHitsExtras {}
  def cloneUnfittedExtras = this
}
object ClassifierNode {
  def apply(params:NodeParams, index:Option[VectorIndex]):ClassifierNode = {
    val ret = ClassifierNode(
      points = ArrayBuffer[MLVector]()
      , params = params
    )
    index match {
      case Some(ix) => ret.points ++= (ix(ret.sequences.flatMap(t => t).distinct) match {case map => ret.sequences.map(tts => tts.flatMap(token => map.get(token)).reduceOption(_.sum(_)).getOrElse(null))})
      case _ =>
    }
    ret
  }
  def apply(encoded:EncodedNode):ClassifierNode = {
    val ret = ClassifierNode(
      points = encoded.points.clone
      , params = encoded.params
    )
    ret.models ++= encoded.deserialize[HashMap[Int, LinearSVCModel]]("models").mapValues(m => WrappedClassifier(m))
    ret
  }
}

case class PerformanceReport(
    threshold:Option[Double]=None
  , precision:Option[Double]=None
  , recall:Option[Double]=None
  , f1Score:Option[Double]=None
  , areaUnderROC:Option[Double]=None
  , rocCurve:Array[(Double, Double)]=Array[(Double, Double)]()
  , accuracy:Option[Double]=None
  , pValue:Option[Double]=None
  , nodeName:String
  , tagId: Option[Int]
  , classId: Int
  , timestamp:Timestamp
  , positiveAnnotations: Int
  , negativeAnnotations: Int
  , positiveAnnotationsChildBrother: Int
  , negativeAnnotationsChildBrother: Int
  , tp:Option[Int]=None
  , fp:Option[Int]=None
  , tn:Option[Int]=None
  , fn:Option[Int]=None
)
