package support.alfresco;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URI;
import java.nio.file.Files;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class FileLoadingProcessor implements Processor {

	@Override
	public void process(Exchange exchange) throws Exception {
		// Get header containing location of file to upload
	    String filePath = exchange.getIn().getHeader("localFile", String.class);
	    String fileType = "Undetermined";  
	    
	    // Create file object and get mimetype
	    if (filePath != null) {
	        URI uri = new URI("file://" + filePath);
		    File file = new File(uri);
		    fileType = Files.probeContentType(file.toPath());  
	
		    if (!file.exists()) {
		        throw new FileNotFoundException(String.format("File %s not found", filePath));
		    }
		    
		    // Add file to message body
		    exchange.getIn().setBody(file);
		    // Configure file mimetype in message
		    exchange.getIn().setHeader("cmis:contentStreamMimeType", fileType);
	    }

	}
}	