package support.alfresco;

import java.util.ArrayList;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class RowProcessor implements Processor {

	@SuppressWarnings("unchecked")
	public void process(Exchange exchange) throws Exception {
    	ArrayList<?> list = (ArrayList<?>) exchange.getProperty("maxTransactionId");
    	Long minId = Long.valueOf((String) exchange.getProperty("lastProcessedTransaction"));
    	Long maxId = minId + 50;
    	
   	 	for (int i = 0; i < list.size(); i++) {
	   	 	Map<String, Long> row = (Map<String, Long>) list.get(i);
			for (Map.Entry<String, Long> entry : row.entrySet()) {
				Long value = Long.valueOf(entry.getValue());
		
			    Long maxTransactionId = Long.valueOf(value);
			    if (maxId > maxTransactionId){
		    		maxId = maxTransactionId;
		    	}
			}
		
	    	exchange.setProperty("maxId", maxId);
			exchange.setProperty("minId", minId);
	    }
	}
}