import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CrptApi {

    private final Semaphore semaphore;
    private final Long intervalMillis;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.semaphore = new Semaphore(requestLimit);
        this.intervalMillis = timeUnit.toMillis(1);
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public void createDocument(Document document, String signature) throws  CrptApiException {
        try {
            semaphore.acquire();
            try {
                String jsonDocument = objectMapper.writeValueAsString(document);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create"))
                        .header("Content-Type", "application/json")
                        .POST(HttpResponse.BodyPublishers.ofString(jsonDocument))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode()!= 200) {
                    throw new CrptApiException("API вернул код ошибки: " + response.statusCode());
                }
            } catch (JsonProcessingException e)  {
                throw new CrptApiException("Ошибка при сериализации документа", e);
            } catch (java.io.IOException | InterruptedException e) {
                throw new CrptApiException("Ошибка при отправке запроса", e);
            } finally {
                schedulePermitRelease();
            }
        } catch (InterruptedException e){
            Thread.currentThread().interrupt();
            throw new CrptException("Операция была прервана");
        }
    }

    private void schedulePermitRelease() {
        CompletableFuture.delayedExecutor(intervalMillis, TimeUnit.MILLISECONDS)
                .execute(semaphore::release);
    }

    public static class Document {

    }

    public static class Description {

    }

    public static class Product  {

    }

    public static void main(String[] args) {

    }

}
