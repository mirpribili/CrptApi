package org.example;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.Deque;
import java.util.LinkedList;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Клиент для работы с API Честного знака (ГИС МТ).
 *
 * Помогает создавать документы для ввода в оборот товара, произведённого в РФ.
 * Ограничивает частоту запросов (например, 100 в минуту).
 * Требует готовый токен для авторизации. Токен получи заранее через УКЭП.
 *
 *
 * <b>Внимание:</b> Все запросы требуют заголовок
 *
 * <code>Authorization: Bearer <token></code>.
 * Время жизни токена - 10 часов.
 * Подробнее об аутентификации и получении токена смотри в разделе 1.1 и 1.2 <a href="https://ismp.crpt.ru/docs/">официальной документации</a>
 * или в файле Opisanie-API-GIS-MP.pdf  (стр. 7-9).
 * <b>Документация по методам:</b> смотри раздел "Ввод в оборот" (PDF, стр. 108+).
 * Ошибки API возвращаются в JSON или XML, всегда читай описание ошибки.
 * Можно расширять: добавляй новые типы документов, меняй стратегию ограничения запросов, реализуй сериализацию в другие форматы.
 *
 * Пример использования:
 *
 * CrptApi api = new CrptApi(TimeUnit.MINUTES, 100, "твой_токен");
 * api.createDocument(document, signature);
 * </pre>
 */
public class CrptApi {
    private static final String API_URL = "https://ismp.crpt.ru/api/v1/lk/documents/create";
    private static final String HEADER_AUTH = "Authorization";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String ACCEPT_JSON = "application/json";

    // Аутентификационный токен для запросов
    private final String authToken;

    // Ограничитель количества запросов (скользящее окно)
    private final Limiter limiter;


    // HTTP-клиент для отправки запросов
    private final HttpClient httpClient;

    /**
     * Создаёт документ для ввода в оборот товара, произведённого в РФ.
     *
     * <p><b>ВАЖНО:</b> Все методы API ГИС МТ требуют передачи в заголовке параметра
     * <code>Authorization: Bearer &lt;token&gt;</code>, где token - аутентификационный токен.
     * Время жизни токена - 10 часов. Токен должен быть получен заранее и передан в конструктор CrptApi.</p>
     *
     * <p><b>Параметр signature:</b> В рамках данного метода (LP_INTRODUCE_GOODS) подпись не используется,
     * но согласно ТЗ она должна передаваться в метод. Для других методов API подпись может быть обязательной.</p>
     *
     * @param document  объект документа (реализует Document)
     * @param signature строка подписи (для совместимости с ТЗ, не используется в данном методе)
     * @throws CrptApiException     если произошла ошибка при запросе
     * @throws InterruptedException если поток был прерван
     */
    public void createDocument(Document document, String signature) throws CrptApiException, InterruptedException {
        if (document == null) {
            throw new IllegalArgumentException("Document must not be null");
        }
        if (signature == null) {
            throw new IllegalArgumentException("Signature must not be null");
        }
        limiter.acquire(); // Ограничение запросов
        try {
            // Сериализация документа в JSON
            String json = document.toJson();

            // Формируем HTTP-запрос
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header(HEADER_AUTH, "Bearer " + authToken)
                    .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                    .header(HEADER_ACCEPT, ACCEPT_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            // Отправляем запрос
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Обработка ответа
            int status = response.statusCode();
            String body = response.body();

            if (status < 200 || status >= 300) {
                String errorMessage = extractErrorMessage(body).orElse("Unknown error");
                throw new CrptApiException(status, errorMessage, body);
            }
        } catch (Exception e) {
            throw new CrptApiException(-1, "Request failed: " + e.getMessage(), null, e);
        } finally {
            limiter.release();
        }
    }

    /**
     * Исключение для ошибок работы с API Честного знака.
     */
    /**
     * Исключение для ошибок API.
     */
    public static class CrptApiException extends Exception {
        private final int code;
        private final String originalResponse;

        public CrptApiException(int code, String message, String originalResponse) {
            super(message);
            this.code = code;
            this.originalResponse = originalResponse;
        }

        public CrptApiException(int code, String message, String originalResponse, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.originalResponse = originalResponse;
        }

        public int getCode() {
            return code;
        }

        public Optional<String> getOriginalResponse() {
            return Optional.ofNullable(originalResponse);
        }
    }

    /**
     * Извлекает error_message из JSON-ответа.
     *
     * @param body тело ответа
     * @return сообщение об ошибке или исходный текст
     */
    /**
     * Пытается извлечь error_message из JSON-ответа.
     */
    private Optional<String> extractErrorMessage(String body) {
        if (body == null) return Optional.empty();
        try {
            ObjectMapper mapper = new ObjectMapper();
            return Optional.ofNullable(mapper.readTree(body).path("error_message").asText(null));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Интерфейс для ограничения запросов.
     */
    private interface Limiter {
        void acquire() throws InterruptedException;

        void release();
    }

    /**
     * Создаёт экземпляр API с ограничением количества запросов.
     *
     * @param timeUnit     единица измерения времени (секунда, минута и т.д.)
     * @param requestLimit максимальное количество запросов в интервале
     * @param authToken    аутентификационный токен
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit, String authToken) {
        this.authToken = authToken;
        this.limiter = new SlidingWindowLimiter(requestLimit, timeUnit, 1); // интервал = 1 timeUnit
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Ограничитель количества запросов за интервал времени (скользящее окно).
     */
    private static class SlidingWindowLimiter implements Limiter {
        private final int limit; // максимальное количество запросов
        private final long intervalMillis; // интервал окна в миллисекундах
        private final Deque<Long> timestamps = new LinkedList<>(); // очередь меток времени

        /**
         * @param limit    максимальное количество запросов
         * @param timeUnit единица времени
         * @param interval интервал окна
         */
        public SlidingWindowLimiter(int limit, TimeUnit timeUnit, int interval) {
            this.limit = limit;
            this.intervalMillis = timeUnit.toMillis(interval);
        }

        /**
         * Блокирует поток, если лимит превышен.
         */
        public synchronized void acquire() throws InterruptedException {
            long now = System.currentTimeMillis();
            // Удаляем устаревшие метки времени
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= intervalMillis) {
                timestamps.pollFirst(); // что делает этот метод
            }
            // Если лимит не превышен - разрешаем запрос
            if (timestamps.size() < limit) {
                timestamps.addLast(now);
                return;
            }
            // Иначе ждём, пока освободится окно
            long waitTime = intervalMillis - (now - timestamps.peekFirst());
            if (waitTime > 0) {
                wait(waitTime);
            }
            // После ожидания пробуем снова
            acquire();
        }

        /**
         * Освобождает окно (вызывается после завершения запроса).
         */
        public synchronized void release() {
            notifyAll();
        }
    }

    /**
     * Интерфейс для сериализации документа в JSON.
     */
    public interface Document {
        /**
         * Сериализация документа в JSON.
         *
         * @return строка JSON
         */
        String toJson();
        // default Optional<String> toXml() { return Optional.empty(); }
        // default Optional<String> toCsv() { return Optional.empty(); }
    }

    /**
     * Документ для ввода в оборот товара, произведенного в РФ (LP_INTRODUCE_GOODS).
     */
    public static class LpIntroduceGoodsDocument implements Document {
        private Description description;
        private String doc_id;
        private String doc_status;
        private String doc_type;
        private Boolean importRequest;
        private String owner_inn;
        private String participant_inn;
        private String producer_inn;
        private String production_date;
        private String production_type;
        private Product[] products;
        private String reg_date;
        private String reg_number;

        // Геттеры и сеттеры
        public Description getDescription() {
            return description;
        }

        public void setDescription(Description description) {
            this.description = description;
        }

        public String getDoc_id() {
            return doc_id;
        }

        public void setDoc_id(String doc_id) {
            this.doc_id = doc_id;
        }

        public String getDoc_status() {
            return doc_status;
        }

        public void setDoc_status(String doc_status) {
            this.doc_status = doc_status;
        }

        public String getDoc_type() {
            return doc_type;
        }

        public void setDoc_type(String doc_type) {
            this.doc_type = doc_type;
        }

        public Boolean getImportRequest() {
            return importRequest;
        }

        public void setImportRequest(Boolean importRequest) {
            this.importRequest = importRequest;
        }

        public String getOwner_inn() {
            return owner_inn;
        }

        public void setOwner_inn(String owner_inn) {
            this.owner_inn = owner_inn;
        }

        public String getParticipant_inn() {
            return participant_inn;
        }

        public void setParticipant_inn(String participant_inn) {
            this.participant_inn = participant_inn;
        }

        public String getProducer_inn() {
            return producer_inn;
        }

        public void setProducer_inn(String producer_inn) {
            this.producer_inn = producer_inn;
        }

        public String getProduction_date() {
            return production_date;
        }

        public void setProduction_date(String production_date) {
            this.production_date = production_date;
        }

        public String getProduction_type() {
            return production_type;
        }

        public void setProduction_type(String production_type) {
            this.production_type = production_type;
        }

        public Product[] getProducts() {
            return products;
        }

        public void setProducts(Product[] products) {
            this.products = products;
        }

        public String getReg_date() {
            return reg_date;
        }

        public void setReg_date(String reg_date) {
            this.reg_date = reg_date;
        }

        public String getReg_number() {
            return reg_number;
        }

        public void setReg_number(String reg_number) {
            this.reg_number = reg_number;
        }

        // Конструктор без параметров
        public LpIntroduceGoodsDocument() {
        }

        /**
         * Сериализация документа в JSON.
         *
         * @return строка JSON
         */
        @Override
        public String toJson() {
            ObjectMapper mapper = new ObjectMapper();
            try {
                return mapper.writeValueAsString(this);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Ошибка сериализации документа: " + e.getMessage(), e);
            }
        }

        /**
         * Вложенный класс для description.
         */
        public static class Description {
            @JsonProperty("participantInn")
            private String participantInn;

            public Description() {
            }

            public Description(String participantInn) {
                this.participantInn = participantInn;
            }

            public String getParticipantInn() {
                return participantInn;
            }

            public void setParticipantInn(String participantInn) {
                this.participantInn = participantInn;
            }
        }

        /**
         * Вложенный класс для products.
         */
        public static class Product {
            private String certificate_document;
            private String certificate_document_date;
            private String certificate_document_number;
            private String owner_inn;
            private String producer_inn;
            private String production_date;
            private String tnved_code;
            private String uit_code;
            private String uitu_code;

            public Product() {
            }

            public String getCertificate_document() {
                return certificate_document;
            }

            public void setCertificate_document(String certificate_document) {
                this.certificate_document = certificate_document;
            }

            public String getCertificate_document_date() {
                return certificate_document_date;
            }

            public void setCertificate_document_date(String certificate_document_date) {
                this.certificate_document_date = certificate_document_date;
            }

            public String getCertificate_document_number() {
                return certificate_document_number;
            }

            public void setCertificate_document_number(String certificate_document_number) {
                this.certificate_document_number = certificate_document_number;
            }

            public String getOwner_inn() {
                return owner_inn;
            }

            public void setOwner_inn(String owner_inn) {
                this.owner_inn = owner_inn;
            }

            public String getProducer_inn() {
                return producer_inn;
            }

            public void setProducer_inn(String producer_inn) {
                this.producer_inn = producer_inn;
            }

            public String getProduction_date() {
                return production_date;
            }

            public void setProduction_date(String production_date) {
                this.production_date = production_date;
            }

            public String getTnved_code() {
                return tnved_code;
            }

            public void setTnved_code(String tnved_code) {
                this.tnved_code = tnved_code;
            }

            public String getUit_code() {
                return uit_code;
            }

            public void setUit_code(String uit_code) {
                this.uit_code = uit_code;
            }

            public String getUitu_code() {
                return uitu_code;
            }

            public void setUitu_code(String uitu_code) {
                this.uitu_code = uitu_code;
            }
        }
    }
}
