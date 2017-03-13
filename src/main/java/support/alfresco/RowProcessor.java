package support.alfresco;

import java.util.ArrayList;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class RowProcessor implements Processor {

	@SuppressWarnings("unchecked")
	public void process(Exchange exchange) throws Exception {
    	ArrayList<?> list = (ArrayList<?>) exchange.getProperty("maxNodeId");
    	Long minId = Long.valueOf((String) exchange.getProperty("lastNodeProcessed"));
    	Long maxId = minId + 100;
    	
   	 	for (int i = 0; i < list.size(); i++) {
	   	 	Map<String, Long> row = (Map<String, Long>) list.get(i);
			for (Map.Entry<String, Long> entry : row.entrySet()) {
				Long value = Long.valueOf(entry.getValue());
		
			    Long maxNodeId = Long.valueOf(value);
			    if (maxId > maxNodeId){
		    		maxId = maxNodeId;
		    	}
			}
	    	exchange.setProperty("maxId", maxId);
			exchange.setProperty("minId", minId);
	    }
	}
}