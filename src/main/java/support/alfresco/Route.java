package support.alfresco;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Route extends RouteBuilder {

	public static String alf1;
	@Value("${lb.alf1}")
    public void setAlf1(String inLbAlf1) {
        alf1 = inLbAlf1;
    }
	
	public static String alf2;
	@Value("${lb.alf2}")
    public void setAlf2(String inLbAlf2) {
        alf2 = inLbAlf2;
    }

	@Override
	public void configure() throws Exception {

		onException(Exception.class)
	    .handled(true)
	    .process(new Processor() {
	         public void process(Exchange exchange) throws Exception {
	               @SuppressWarnings("unused")
	               Exception exception = (Exception) exchange.getProperty(Exchange.EXCEPTION_CAUGHT);
	               //log, email, reroute, etc.
	         }
	    });
		
		
		// Route 1 - Retrieves information about new transactions
		from("jms:alfresco.lastTransactionProcessed")
		.choice()				
			.when(body().isNotNull())
				.setProperty("lastProcessedTransaction", simple("${body}"))
				.to("sql: select max(id) from alf_transaction")
				.setProperty("maxTransactionId", simple("${body}"))
				.process(new RowProcessor())
		        .choice()				
					.when(body().isNotNull())
						.to("sql: select anp.node_id, anp.string_value, als.protocol, als.identifier " + 
						"from alf_node_properties as anp, alf_node as an, alf_store as als " +
						"where node_id in (select id from alf_node where transaction_id > :#${property.minId} " +
						"and transaction_id <= :#${property.maxId}) " +
						"and an.type_qname_id in (select id from alf_qname where local_name = 'content') " + 
						"and (anp.node_id = an.id and anp.qname_id in (select id from alf_qname where local_name = 'name')) " +
						"and als.id in (select an.store_id from alf_node)" +
						"order by node_id")
						.choice().when().simple("${body} != '[]'").log("${body}").endChoice()
						.setBody(simple("${property.maxId}", String.class))
						.delay(5000)
						.to("jms:alfresco.lastTransactionProcessed")
				.endChoice()
			.endChoice();
		

		/* Sample messages. Multiple messages can be sent in same file...each message should be on a single line
		 * Create folder:
		 * <file name="folderName" path="/Shared/folderName" objectTypeId="folder" />

		 * Create file
		 * <file name="testFile.docx" path="/Shared" objectTypeId="document" localFile="/tmp/outbox/testFile.docx" />	
		 */

		
		// Route 2 - Create folder and documents 
		from("jms:alfresco.uploadNodes")
			.log("Body: ${body}")
			.marshal().string("UTF-8")
			// Process each line individually
			.split(body().tokenize("\n")).streaming()
			// Throttling traffic - no more than 10 docs every 5 seconds
			.throttle(10).timePeriodMillis(5000)
			.choice()	
				// If the node is a folder
				.when().xpath("/file/@objectTypeId = 'folder'")
					.log("Creating a folder")
					// Set properties using cmis naming
					.setHeader("cmis:name").xpath("/file/@name", String.class)
					.setHeader("cmis:objectTypeId", simple("cmis:folder"))
					.setHeader("CamelCMISFolderPath").xpath("/file/@path", String.class)
					.loadBalance().roundRobin().to(alf1, alf2)
					.endChoice()
				// If the node is a document
				.when().xpath("/file/@objectTypeId = 'document'")
					.log("Creating a file")
					// Set properties using cmis naming					
					.setHeader("cmis:name").xpath("/file/@name", String.class)
					.setHeader("cmis:objectTypeId", simple("cmis:document"))
					.setHeader("localFile").xpath("/file/@localFile", String.class)
					.setHeader("CamelCMISFolderPath").xpath("/file/@path", String.class)
					.process(new FileLoadingProcessor())					
					// Load Balance traffic between 2 Alfresco nodes
					.loadBalance().roundRobin().to(alf1, alf2)
				.endChoice();				
		
		// Execute cmis query, download content and properties, compress it and upload it to Box
		from("jms:alfresco.downloadNodes")
		.setBody(xpath("/search/@query", String.class))
		.setHeader("CamelCMISRetrieveContent", constant(true))
		.to("cmis://http://localhost:8080/alfresco/cmisatom?username=admin&password=admin&queryMode=true")
		.process(new FileContentProcessor());
		
		from("file:/tmp/outbox?antInclude=*").marshal().zipFile().to("file:/tmp/box");
		
		from("file:/tmp/metadata?antInclude=*").marshal().zipFile().to("file:/tmp/box");
		
		from("file:/tmp/box?noop=false&recursive=true&delete=true")
	    .to("box://files/uploadFile?inBody=fileUploadRequest");
		
		
		
		/* 
		// Test route read/write to queues
		from("timer://runOnce?delay=5s&period=5s")		
		.from("jms:test.input")
		.log("jms up and running: ${body}")
		.to("jms:test.output");
		*/
		
		/*
		// Test route to move files between folders
		from("file:/tmp/inbox?noop=false&recursive=true&delete=true")
		.log("Starting to process file: ${header.CamelFileName}")
		.to("file:/tmp/outbox");
		 */
		
		/*
		 *  CMIS routes
		 */
		
		// Using timer to run job just once
		//from("timer://runOnce?repeatCount=1&delay=5000")
		//.to("cmis://http://localhost:8080/alfresco/cmisatom?username=admin&password=admin&readContent=true")
		//.to("file:/tmp/outbox");
				
	
		
		/*
		// Download text file content and display in body
		from("timer://runOnce?repeatCount=1&delay=5000")
		.to("http4://localhost:8080/alfresco/service/api/node/content/workspace/SpacesStore/4b7f3ceb-3808-4151-8faa-ee80b08c824f?authMethod=Basic&authUsername=admin&authPassword=admin")
		.log("${body}");
 
		// Download binary i.e. pdf file content to folder /tmp/outbox
		from("timer://runOnce?repeatCount=1&delay=5000")
		.to("http4://localhost:8080/alfresco/service/api/node/content/workspace/SpacesStore/886c1c15-8371-4f72-8b1a-7e1b566621be?authMethod=Basic&authUsername=admin&authPassword=admin")
  	    .convertBodyTo(byte[].class, "iso-8859-1")
		.to("file:/tmp/outbox");  
		
		// Upload file to site folder (create or update if already there)
		from("timer://runOnce?repeatCount=1&delay=5000")
		.setHeader("CamelHttpMethod", constant("POST"))
		.process(new MultiPartUpload())
		.to("http4://localhost:8080/alfresco/service/api/upload?authMethod=Basic&authUsername=admin&authPassword=admin");	

		/* Sync data to another repository
		from("cmis://http://localhost:8080/alfresco/cmisatom?repositoryId=1c194391-a1d0-420b-bd1a-04e3aa140392&username=user1&password=user1")
		.log("Processing file: ${header.CamelFileName}")
		.setHeader("CamelCMISFolderPath", simple("/Shared")) 
        .setHeader("cmis:name", simple("${header.CamelFileName}")) 
        .setHeader("cmis:objectTypeId", simple("cmis:document"))
		//.setHeader("cmis:contentStreamMimeType", simple("text/plain"))			
		.to("cmis://http://localhost:8080/alfresco/cmisatom?repositoryId=1c194391-a1d0-420b-bd1a-04e3aa140392&username=user1&password=user1");
		*/	
	}
}
