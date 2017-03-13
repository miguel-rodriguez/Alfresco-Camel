package support.alfresco;

import java.io.File;

import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.StringBody;

import java.io.FileNotFoundException;
import java.net.URI;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class MultiPartUpload implements Processor {

	@Override
	public void process(Exchange exchange) throws Exception {
		String filename = (String) exchange.getProperty("file");
		String destNodeRef = (String) exchange.getProperty("destNodeRef");
        final StringBody destination = new StringBody(destNodeRef, ContentType.MULTIPART_FORM_DATA);
        //final StringBody siteId = new StringBody("testSite", ContentType.MULTIPART_FORM_DATA);
        //final StringBody containerId = new StringBody("documentLibrary", ContentType.MULTIPART_FORM_DATA);
        //final StringBody uploadDirectory = new StringBody("testFolder", ContentType.MULTIPART_FORM_DATA);

        URI uri = new URI(filename);
	    File file = new File(uri);
	    if (!file.exists()) {
	        throw new FileNotFoundException(String.format("File %s not found", filename));
	    }

	    MultipartEntityBuilder multipartEntityBuilder = MultipartEntityBuilder.create();
        multipartEntityBuilder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
		//multipartEntityBuilder.addPart("siteid", siteId);
		//multipartEntityBuilder.addPart("containerid", containerId);
		multipartEntityBuilder.addPart("destination", destination);
		//multipartEntityBuilder.addPart("uploaddirectory", uploadDirectory);
        multipartEntityBuilder.addBinaryBody("filedata", file);
        exchange.getIn().setBody(multipartEntityBuilder.build()); 
	}
}	