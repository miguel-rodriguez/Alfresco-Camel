package support.alfresco;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
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
	
	public static String alfrescoSender;
	@Value("${alfresco.sender}")
    public void setAlfrescoSender(String inSender) {
        alfrescoSender = inSender;
    }

	public static String alfrescoReceiver;
	@Value("${alfresco.receiver}")
    public void setAlfrescoReceiver(String inReceiver) {
        alfrescoReceiver = inReceiver;
    }
	
	public static String contentStoreDir;
	@Value("${alfresco.contenStoreDir}")
    public void setDirRoot(String inContentStoreDir) {
        contentStoreDir = inContentStoreDir;
    }
	
	@Override
	public void configure() throws Exception {

		
		// Capture all exceptions and show error without stack trace
		onException(Exception.class)
	    .handled(true)
	    .process(new Processor() {
	         public void process(Exchange exchange) throws Exception {
	               @SuppressWarnings("unused")
	               Exception exception = (Exception) exchange.getProperty(Exchange.EXCEPTION_CAUGHT);

	         }
	    });
		
		
		/////////////////////////////////////////////////////////////////
		// Route 1 - Retrieves information from the DB about new nodes //
		/////////////////////////////////////////////////////////////////
		from("jms:alfresco.lastNodeProcessed")
		.choice()				
			.when(body().isNotNull())
				.setProperty("lastNodeProcessed", simple("${body}"))
				.to("sql: select max(id) from alf_node")
				.setProperty("maxNodeId", simple("${body}"))
				.process(new RowProcessor())
		        .choice()				
					.when(body().isNotNull())
						.to("sql: select anp.node_id, anp.string_value, an.audit_modifier, als.protocol, als.identifier, an.uuid " + 
						"from alf_node_properties as anp, alf_node as an, alf_store as als " +
						"where an.id > :#${property.minId} " +
						"and an.id <= :#${property.maxId} " +
						"and an.type_qname_id in (select id from alf_qname where local_name = 'content') " + 
						"and (anp.node_id = an.id and anp.qname_id in (select id from alf_qname where local_name = 'name')) " +
						"and als.id in (select an.store_id from alf_node)" +
						"order by node_id")
					.choice().when().simple("${body} != '[]'")
					// Split the response to process nodes one by one
					.split(body()).streaming()
					.marshal().json(JsonLibrary.Jackson)
					.log("${body}").endChoice()
					.end()
					.to("bean:MyOrderService?method=buildCombinedResponse")
					.setBody(simple("${property.maxId}", String.class))
					.to("jms:alfresco.lastNodeProcessed")
					.delay(5000)
			.endChoice()
		.endChoice();
		/////////////////////////////////////////////////////		
		

		
		////////////////////////////////////////////
		// Route 2 - Create folders and documents //
		////////////////////////////////////////////
		
		/* 
		 * Sample messages. Multiple messages can be sent in same file...each message should be on a single line
		 * Create folder:
		 * <file name="myNewFolder" path="/Shared" objectTypeId="folder" />

		 * Create file
		 * <file name="myNewDoc.docx" path="/Shared" objectTypeId="document" localFile="/tmp/inbox/Test.docx" />	
		 */

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
		/////////////////////////////////////////////////////		

		
		////////////////////////////////////////////
		// Route 3 - Download Alfresco documents  //
		////////////////////////////////////////////
		/* 
		* Execute cmis query, download content and properties, compress it and upload it to Box 
		* i.e. 
		* {"objectTypeId":"search", "query":"SELECT * FROM cmis:document WHERE IN_FOLDER ('workspace://SpacesStore/2ca7b7e1-085b-47ba-b008-a7dc4c4ef0a6') and cmis:objectTypeId='cmis:document'"}
		*/
		from("jms:alfresco.downloadNodes")
		.log("Running query: ${body}")
		.setHeader("CamelCMISRetrieveContent", constant(true))
		.to(alfrescoSender + "&queryMode=true")
		.process(new FileContentProcessor());
		/////////////////////////////////////////////////////		
		
		
		/////////////////////////////////////////////////////////////
		// Routes 4, 5 and 6 - Move documents and metadata to Box  //
		/////////////////////////////////////////////////////////////
		from("file:/tmp/downloads?antInclude=*")
		.marshal().zipFile()
		.to("file:/tmp/box");
		
		from("file:/tmp/metadata?antInclude=*")
		.marshal().zipFile()
		.to("file:/tmp/box");
		
		from("file:/tmp/box?noop=false&recursive=true&delete=true")
	    .to("box://files/uploadFile?inBody=fileUploadRequest");
		/////////////////////////////////////////////////////		
		
	

		
		////////////////////////////////////////////////////
		// Route 6 - clone folder structure and documents //
		////////////////////////////////////////////////////
		
		// Execute cmis query and copy 
		//This is the initial nodeRef so we need the info about the root folder to get its children
		from("jms:alfresco.nodeRef")
		.log("Cloning folders and files under ${body}")
		.setProperty("rootNodeRef", simple("${body}"))
		
		// Get info about root folder
		.setBody(simple("SELECT * FROM cmis:folder F WHERE F.cmis:objectId='${property.rootNodeRef}'"))
		.setHeader("CamelCMISRetrieveContent", constant(false))
		.to(alfrescoSender + "&queryMode=true")
		.split(body()).streaming()
		.log("test1")
			.marshal().json(JsonLibrary.Jackson)
			.setProperty("cmis:path").jsonpath("cmis:path")
			
			// Get the documents under root folder
			.setBody(simple("SELECT * FROM cmis:document WHERE IN_FOLDER ('${property.rootNodeRef}') and cmis:objectTypeId='cmis:document'"))
			.setHeader("CamelCMISRetrieveContent", constant(false))
			.to(alfrescoSender + "&queryMode=true")
			.split(body(), new MyOrderStrategy()).streaming()
			.marshal().json(JsonLibrary.Jackson)
			.setBody(body().regexReplaceAll("store:/", contentStoreDir))
	
			// And add the files at the other end
			.setHeader("cmis:name").jsonpath("cmis:name")
			.setHeader("cmis:objectTypeId", simple("cmis:document"))
			.setHeader("CamelCMISFolderPath", simple("${property.cmis:path}"))
			.setHeader("localFile").jsonpath("cmis:contentStreamId")
			.process(new FileLoadingProcessor())
			.to(alfrescoReceiver)
			// Wait for the other tasks to complete
			//.to("bean:MyOrderService?method=handleOrder")
	    .end()
	    .to("bean:MyOrderService?method=buildCombinedResponse")
				.log("test2")

		// Now create the folder structure
		.setBody(simple("SELECT * FROM cmis:folder F WHERE IN_TREE (F,'${property.rootNodeRef}') order by cmis:name"))
		.log("test3")
		.setHeader("CamelCMISRetrieveContent", constant(false))
		.to(alfrescoSender + "&queryMode=true")
		.split(body(), new MyOrderStrategy()).streaming()
			.marshal().json(JsonLibrary.Jackson)
			    .log("test4")

			// Create the folders at the other end
			.setHeader("cmis:name").jsonpath("cmis:name")
			.setHeader("cmis:objectTypeId", simple("cmis:folder"))
			.setProperty("cmis:path").jsonpath("cmis:path")
			.setProperty("alfcmis:nodeRef").jsonpath("alfcmis:nodeRef")
			.process(new Processor() {
		         public void process(Exchange exchange) throws Exception {
						String path = (String) exchange.getProperty("cmis:path");
						exchange.setProperty("cmis:folderPath",  path.substring(0, path.lastIndexOf("/")));
		         }
		    })
			.setHeader("CamelCMISFolderPath", simple("${property.cmis:folderPath}"))
			.to(alfrescoReceiver)
	
		    // Get the documents under the folder
			.setBody(simple("SELECT * FROM cmis:document WHERE IN_FOLDER ('${property.alfcmis:nodeRef}') and cmis:objectTypeId='cmis:document'"))
			.setHeader("CamelCMISRetrieveContent", constant(false))
			.to(alfrescoSender + "&queryMode=true")
			.split(body(), new MyOrderStrategy()).streaming()
			.log("test5")
				.marshal().json(JsonLibrary.Jackson)
				.setBody(body().regexReplaceAll("store:/", contentStoreDir))
				.log("Adding stuff")
				// And add the documents at the other end
				.setHeader("cmis:name").jsonpath("cmis:name")
				.setHeader("cmis:objectTypeId", simple("cmis:document"))
				.setHeader("CamelCMISFolderPath", simple("${property.cmis:path}"))
				.setHeader("localFile").jsonpath("cmis:contentStreamId")
				.process(new FileLoadingProcessor())
				.to(alfrescoReceiver)
				// Wait for the other tasks to complete
				//.to("bean:MyOrderService?method=handleOrder")
		    .end()
		    .to("bean:MyOrderService?method=buildCombinedResponse")
			//.to("bean:MyOrderService?method=handleOrder")
	    .end()
	    .to("bean:MyOrderService?method=buildCombinedResponse")	    
	    .log("Cloning completed");
   		/////////////////////////////////////

		
		
		///////////////////////////////////////////////////////////
		// Route 7 - Upload document to Alfresco using REST API  //
		///////////////////////////////////////////////////////////
		/* 
		* Execute HTTP request to upload doc to destination node ref or to a site 
		* i.e. upload to destination node ref
		* {"file":"file:///tmp/inbox/Test.docx", "destNodeRef":"workspace://SpacesStore/2ca7b7e1-085b-47ba-b008-a7dc4c4ef0a6"}

		*/
		// Upload file to site folder (create or update if already there)
		from("jms:alfresco.multipartUpload")
		.setProperty("file").jsonpath("file")
		.setProperty("destNodeRef").jsonpath("destNodeRef")
		.setHeader("CamelHttpMethod", constant("POST"))
		.log("Uploading file ${property.file} to ${property.destNodeRef}")
		.process(new MultiPartUpload())
		.to("http4://localhost:8080/alfresco/service/api/upload?authMethod=Basic&authUsername=admin&authPassword=admin");	
   		/////////////////////////////////////

		
				
		/*
		 *  CMIS routes
		 */
		
	
		
		/*
		// Download text file content and display in body
		from("timer://runOnce?repeatCount=1&delay=5000")
		.to("http4://localhost:8080/alfresco/service/api/node/content/workspace/SpacesStore/4b7f3ceb-3808-4151-8faa-ee80b08c824f?authMethod=Basic&authUsername=admin&authPassword=admin")
		.log("${body}");
 		*/
		
		/*
		// Download binary i.e. pdf file content to folder /tmp/outbox
		from("timer://runOnce?repeatCount=1&delay=5000")
		.to("http4://localhost:8080/alfresco/service/api/node/content/workspace/SpacesStore/886c1c15-8371-4f72-8b1a-7e1b566621be?authMethod=Basic&authUsername=admin&authPassword=admin")
  	    .convertBodyTo(byte[].class, "iso-8859-1")
		.to("file:/tmp/outbox");  
		*/
		
				
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
