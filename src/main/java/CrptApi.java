import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CrptApi {

    private final Semaphore semaphore;
    private final Long intervalMillis;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService scheduler;

    private static final Logger logger = LoggerFactory.getLogger(CrptApi.class);


    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.semaphore = new Semaphore(requestLimit);
        this.intervalMillis = timeUnit.toMillis(1);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.scheduler = Executors.newScheduledThreadPool(1);
    }


    public void createDocument(Document document, String signature) throws CrptApiException {
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                System.out.println("Попытка получить разрешение: " + Thread.currentThread().getName());
                semaphore.acquire();
                System.out.println("Разрешение получено: " + Thread.currentThread().getName());
                try {
                    String jsonDocument = objectMapper.writeValueAsString(document);
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer YOUR_ACCESS_TOKEN")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonDocument))
                            .timeout(Duration.ofSeconds(30))
                            .build();

                    logger.info("Отправка документа: {}", document.docId);
                    logger.debug("Тело запроса: {}", jsonDocument);

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    logger.info("Получен ответ с кодом: {}", response.statusCode());

                    if (response.statusCode() == 429) {
                        throw new TooManyRequestsException();
                    }

                    if (response.statusCode() != 200) {
                        throw new CrptApiException("API вернул код ошибки: " + response.statusCode());
                    }

                    return;

                } catch (TooManyRequestsException e) {
                    Thread.sleep(calculateBackoff(retryCount));
                    retryCount++;
                } catch (Exception e) {
                    throw new CrptApiException("Ошибка при отправке запроса", e);
                } finally {
                    schedulePermitRelease();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CrptApiException("Операция была прервана");
            }
        }
        throw new CrptApiException("Превышено максимального количество попыток");
    }


    private long calculateBackoff(int retryCount) {
        return (long) Math.pow(2, retryCount) * 1000;
    }


    private void schedulePermitRelease() {
        scheduler.schedule(() -> {
            semaphore.release();
            System.out.println("Разрешение освобождено: " + Thread.currentThread().getName());
        }, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }


    public static class Document {
        @JsonProperty("description")
        public Description description;
        @JsonProperty("doc_id")
        public String docId;
        @JsonProperty("doc_status")
        public String docStatus;
        @JsonProperty("doc_type")
        public String docType;
        @JsonProperty("importRequest")
        public boolean importRequest;
        @JsonProperty("owner_inn")
        public String ownerInn;
        @JsonProperty("participant_inn")
        public String participantInn;
        @JsonProperty("producer_inn")
        public String producerInn;
        @JsonProperty("production_date")
        public LocalDate productionDate;
        @JsonProperty("production_type")
        public String productionType;
        @JsonProperty("products")
        public List<CrptApi.Product> products;
        @JsonProperty("reg_date")
        public LocalDate regDate;
        @JsonProperty("reg_number")
        public String regNumber;
    }


    public static class Description {
        @JsonProperty("participantInn")
        public String participantInn;
    }


    public static class Product {
        @JsonProperty("certificate_document")
        public String certificateDocument;
        @JsonProperty("certificate_document_date")
        public LocalDate certificateDocumentDate;
        @JsonProperty("certificate_document_number")
        public String certificateDocumentNumber;
        @JsonProperty("owner_inn")
        public String ownerInn;
        @JsonProperty("producer_inn")
        public String producerInn;
        @JsonProperty("production_date")
        public LocalDate productionDate;
        @JsonProperty("tnved_code")
        public String tnvedCode;
        @JsonProperty("uit_code")
        public String uitCode;
        @JsonProperty("uitu_code")
        public String uituCode;
    }


    public class TooManyRequestsException extends Exception {
        public TooManyRequestsException() {
            super("Превышен лимит запросов к API");
        }
    }


    public class CrptApiException extends Exception {
        public CrptApiException(String message) {
            super(message);
        }

        public CrptApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }


    private static Document createFakeDocument() {
        Document document = new Document();
        document.description = new Description();
        document.description.participantInn = "1234567890";
        document.docId = "doc123";
        document.docStatus = "draft";
        document.docType = "LP_INTRODUCE_GOODS";
        document.importRequest = true;
        document.ownerInn = "0987654321";
        document.participantInn = "1234567890";
        document.producerInn = "5678901234";
        document.productionDate = LocalDate.now().minusDays(500);
        document.productionType = "OWN_PRODUCTION";

        Product product = new Product();
        product.certificateDocument = "cert123";
        product.certificateDocumentDate = LocalDate.now().minusMonths(5);
        product.certificateDocumentNumber = "cert-num-456";
        product.ownerInn = "0987654321";
        product.producerInn = "5678901234";
        product.productionDate = LocalDate.now().minusDays(500);
        product.tnvedCode = "8544499108";
        product.uitCode = "uit123456";
        product.uituCode = "uitu789012";

        document.products = List.of(product);
        document.regDate = LocalDate.now();
        document.regNumber = "reg-num-789";

        return document;
    }

    public static void main(String[] args) {
        CrptApi api = new CrptApi(TimeUnit.SECONDS, 5);
        Document document = createFakeDocument();

        int totalThreads = 20;
        CountDownLatch latch = new CountDownLatch(totalThreads);
        for (int i = 0; i < totalThreads; i++) {
            new Thread(() -> {
                try {
                    api.createDocument(document, "fake_signature");
                } catch (CrptApiException e) {
                    System.err.println("Ошибка при создании документа: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        api.shutdown();
    }

}
