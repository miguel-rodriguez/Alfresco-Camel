package support.alfresco;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.io.FilenameUtils;
import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileContentProcessor implements Processor {
	public static String downloadFolder;
	@Value("${download.folder}")
    public void setDownloadFolder(String inDownloadFolder) {
        downloadFolder = inDownloadFolder;
    }
	
	public static String metadataFolder;
	@Value("${metadata.folder}")
    public void setMetadataFolder(String inMetadataFolder) {
		metadataFolder = inMetadataFolder;
    }
    
	@SuppressWarnings("rawtypes")
	@Override
	public void process(Exchange exchange) throws Exception {
		InputStream in;;
		OutputStream out;
        String filename = null;
        String xmlMetadata = "";
        
		@SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = exchange.getIn().getBody(List.class);
        
		System.out.println("Found " + documents.size() + " file(s)");

	      Iterator<Map<String, Object>> iterator = documents.iterator();
	      while(iterator.hasNext()) {
	          HashMap entry = (HashMap) iterator.next();
	          
	           if (entry.get("CamelCMISContent") != null){
	        	   filename = (String) entry.get("alfcmis:nodeRef");
	        	   filename = filename.replaceAll(":", "");
	        	   filename = filename.replaceAll("//", "-");
	        	   filename = filename.replaceAll("/", "-");
	        	   
	        	    String ext = FilenameUtils.getExtension((String) entry.get("cmis:name"));
	        	   
					in = (InputStream) entry.get("CamelCMISContent");
					out = new FileOutputStream(new File(downloadFolder + "/content-"  + filename + "." + ext));
					IOUtils.copy(in,out);
					in.close();
					out.close();	

					xmlMetadata = "<file ";

				    Iterator it = entry.entrySet().iterator();
				    while (it.hasNext()) {
				        Map.Entry pair = (Map.Entry)it.next();
		        		xmlMetadata = xmlMetadata + pair.getKey() + "=\"" + pair.getValue() + "\" ";
		        		System.out.println(pair.getKey() + "=" + pair.getValue());

		        	}
		        	xmlMetadata = xmlMetadata + " />\n";
		        	System.out.println("");
				    java.io.FileWriter fw = new java.io.FileWriter(metadataFolder + "/metadata-" + filename);
				    fw.write(xmlMetadata);
				    fw.close();
	           }
	      }
	}
}	