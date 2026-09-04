package store.moeum.moeum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MoeumApplication {

	public static void main(String[] args) {
		SpringApplication.run(MoeumApplication.class, args);
	}

}
