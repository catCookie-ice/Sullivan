package sullivan.serverjava;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@MapperScan("sullivan.serverjava.mapper")
public class ServerJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerJavaApplication.class, args);
    }

}
