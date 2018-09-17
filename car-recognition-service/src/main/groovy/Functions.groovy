import bytekast.stdlib.aws.lambda.RequestContext
import bytekast.stdlib.aws.lambda.Response
import bytekast.stdlib.aws.util.SqsUtil
import com.algorithmia.Algorithmia
import com.algorithmia.AlgorithmiaClient
import com.algorithmia.algo.AlgoResponse
import com.algorithmia.algo.Algorithm
import com.amazonaws.services.lambda.runtime.Context
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field
final JsonSlurper jsonSlurper = new JsonSlurper()

def detectImage(String imageUrl, Context context) {
  AlgorithmiaClient client = Algorithmia.client(System.getenv('ALGORITHMIA_API_KEY'))
  Algorithm algo = client.algo("LgoBE/CarMakeandModelRecognition/0.3.14")
  AlgoResponse result = algo.pipe(imageUrl)
  JsonSlurper.newInstance().parseText(result.asJsonString())
}

def sendImage(Map input, Context context) {
  final inputQueueUrl = System.getenv('INPUT_QUEUE_URL')
  final request = new RequestContext().input(input).context(context)
  final imageUrl = request.httpBody()
  SqsUtil.instance.sendSQSMessage(inputQueueUrl, imageUrl)
  new Response().statusCode(200).body("QUEUED: ${imageUrl}")
}

def processImages(Map event, Context context) {
  final records = event?.Records
  final outputQueueUrl = System.getenv('OUTPUT_QUEUE_URL')
  records?.each {
    final imageUrl = it?.body?.toString()
    final result = detectImage(imageUrl, context)
    SqsUtil.instance.sendSQSMessage(outputQueueUrl, JsonOutput.toJson([
      image : imageUrl,
      result: result
    ]))
  }
}

def getResults(def input, Context context) {
  final outputQueueUrl = System.getenv('OUTPUT_QUEUE_URL')
  final result = SqsUtil.instance.getSQSMessages(outputQueueUrl)?.body?.collect { jsonSlurper.parseText(it) }
  new Response().statusCode(200).body(JsonOutput.prettyPrint(JsonOutput.toJson(result)))
}