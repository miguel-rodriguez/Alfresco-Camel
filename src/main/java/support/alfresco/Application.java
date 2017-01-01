package support.alfresco;

import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.ImportResource;

@ImportResource("applicationContext.xml")
public class Application {
	public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
     }
}
