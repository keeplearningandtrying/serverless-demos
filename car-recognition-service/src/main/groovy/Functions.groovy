import bytekast.stdlib.aws.lambda.RequestContext
import bytekast.stdlib.aws.lambda.Response
import bytekast.stdlib.aws.util.S3Util
import bytekast.stdlib.aws.util.SqsUtil
import com.algorithmia.Algorithmia
import com.algorithmia.AlgorithmiaClient
import com.algorithmia.algo.AlgoResponse
import com.algorithmia.algo.Algorithm
import com.amazonaws.services.lambda.runtime.Context
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

class Functions {

  final inputQueueUrl = System.getenv('INPUT_QUEUE_URL')
  final bucket = System.getenv('OUTPUT_S3_BUCKET')
  final JsonSlurper jsonSlurper = new JsonSlurper()
  final s3client = S3Util.instance.s3Client()

  def detectImage(String imageUrl, Context context) {
    AlgorithmiaClient client = Algorithmia.client(System.getenv('ALGORITHMIA_API_KEY'))
    Algorithm algo = client.algo("LgoBE/CarMakeandModelRecognition/0.3.14")
    AlgoResponse result = algo.pipe(imageUrl)
    JsonSlurper.newInstance().parseText(result.asJsonString())
  }

  def sendImage(Map input, Context context) {
    try {
      final request = new RequestContext().input(input).context(context)
      final imageUrl = request.httpBody()
      SqsUtil.instance.sendSQSMessage(inputQueueUrl, imageUrl)
      new Response().statusCode(200).body("QUEUED: ${imageUrl}")
    } catch (e) {
      new Response().statusCode(500).body(e.message)
    }
  }

  def processImages(Map event, Context context) {
    event?.Records?.each { record ->
      final imageUrl = record?.body?.toString() ?: "Unknown Image"
      def jsonOutput
      try {
        final result = detectImage(imageUrl, context)
        jsonOutput = JsonOutput.toJson([image: imageUrl, result: result])
      } catch (e) {
        jsonOutput = JsonOutput.toJson([image: imageUrl, error: e.message])
      }

      S3Util.instance.putS3Object(s3client, bucket, "${UUID.randomUUID()}.json".toString(), JsonOutput.prettyPrint(jsonOutput))
    }
  }

  def getResults(Context context) {
    try {

      final items = S3Util.instance.listBucketItems(s3client, bucket)
      final result = items?.collect {
        jsonSlurper.parseText(S3Util.instance.getS3ObjectContent(s3client, bucket, it.key))
      } ?: []

      new Response().statusCode(200).body(JsonOutput.prettyPrint(JsonOutput.toJson(result)))
    } catch (e) {
      new Response().statusCode(500).body(e.message)
    }
  }
}