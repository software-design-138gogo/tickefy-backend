package com.tickefy.payment.modules.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "app.sepay.mode", havingValue = "real")
public class RealSePayClient implements SePayClient {

    private static final Logger log = LoggerFactory.getLogger(RealSePayClient.class);

    private static final int TRANSACTION_LIMIT = 50;
    private static final Map<String, String> BANK_NAME_MAP = Map.ofEntries(
            Map.entry("VCB", "Vietcombank"),
            Map.entry("VIETCOMBANK", "Vietcombank"),
            Map.entry("MB", "MBBank"),
            Map.entry("MBBANK", "MBBank"),
            Map.entry("TCB", "Techcombank"),
            Map.entry("TECHCOMBANK", "Techcombank"),
            Map.entry("ACB", "ACB"),
            Map.entry("BIDV", "BIDV"),
            Map.entry("VTB", "VietinBank"),
            Map.entry("CTG", "VietinBank"),
            Map.entry("VIETINBANK", "VietinBank"),
            Map.entry("TPB", "TPBank"),
            Map.entry("TPBANK", "TPBank"),
            Map.entry("VPB", "VPBank"),
            Map.entry("VPBANK", "VPBank"),
            Map.entry("MSB", "MSB"),
            Map.entry("SHB", "SHB"));

    private final String baseUrl;
    private final String apiToken;
    private final String accountNumber;
    private final String bankCode;
    private final String accountName;
    private final String qrBaseUrl;
    private final String qrTemplate;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public RealSePayClient(
            @Value("${app.sepay.base-url}") String baseUrl,
            @Value("${app.sepay.api-token}") String apiToken,
            @Value("${app.sepay.account-number}") String accountNumber,
            @Value("${app.sepay.bank-code}") String bankCode,
            @Value("${app.sepay.account-name}") String accountName,
            @Value("${app.sepay.qr-base-url:https://qr.sepay.vn/img}") String qrBaseUrl,
            @Value("${app.sepay.qr-template:compact}") String qrTemplate,
            @Value("${app.sepay.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${app.sepay.read-timeout:PT10S}") Duration readTimeout,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        this(
                baseUrl,
                apiToken,
                accountNumber,
                bankCode,
                accountName,
                qrBaseUrl,
                qrTemplate,
                buildRestClient(baseUrl, connectTimeout, readTimeout, restClientBuilder),
                objectMapper);
    }

    RealSePayClient(
            String baseUrl,
            String apiToken,
            String accountNumber,
            String bankCode,
            String accountName,
            String qrBaseUrl,
            String qrTemplate,
            RestClient restClient) {
        this(
                baseUrl,
                apiToken,
                accountNumber,
                bankCode,
                accountName,
                qrBaseUrl,
                qrTemplate,
                restClient,
                new ObjectMapper());
    }

    RealSePayClient(
            String baseUrl,
            String apiToken,
            String accountNumber,
            String bankCode,
            String accountName,
            String qrBaseUrl,
            String qrTemplate,
            RestClient restClient,
            ObjectMapper objectMapper) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiToken = nullToBlank(apiToken);
        this.accountNumber = nullToBlank(accountNumber);
        this.bankCode = nullToBlank(bankCode);
        this.accountName = nullToBlank(accountName);
        this.qrBaseUrl = stripTrailingSlash(qrBaseUrl);
        this.qrTemplate = nullToBlank(qrTemplate).isBlank() ? "compact" : qrTemplate;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public CreateQrResult createQr(UUID paymentId, long amount, String currency, UUID orderId) {
        validateQrConfig();
        String transferContent = buildTransferContent(orderId);
        String qrImageUrl = buildQrImageUrl(amount, transferContent);

        log.info(
                "RealSePay.createQr paymentId={} orderId={} amount={} transferContent={}",
                paymentId,
                orderId,
                amount,
                transferContent);
        return new CreateQrResult(transferContent, qrImageUrl, qrImageUrl);
    }

    @Override
    public QueryStatusResult queryStatus(String gatewayTransactionId) {
        return queryStatus(gatewayTransactionId, null);
    }

    @Override
    public QueryStatusResult queryStatus(String gatewayTransactionId, Long expectedAmount) {
        if (gatewayTransactionId == null || gatewayTransactionId.isBlank()) {
            return new QueryStatusResult(null, "PENDING");
        }
        if (apiToken.isBlank()) {
            throw new PaymentGatewayException("SePay API token is not configured");
        }

        String search = gatewayTransactionId.trim();
        log.debug("RealSePay.queryStatus searching transferContent={}", search);

        try {
            JsonNode root = restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path("/v2/transactions")
                                .queryParam("q", search)
                                .queryParam("per_page", TRANSACTION_LIMIT)
                                .queryParam("transfer_type", "in");
                        if (expectedAmount != null) {
                            builder.queryParam("amount_in_min", expectedAmount)
                                    .queryParam("amount_in_max", expectedAmount);
                        }
                        return builder.build();
                    })
                    .header("Authorization", "Bearer " + apiToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode transactions = extractTransactions(root);
            if (transactions == null || !transactions.isArray() || transactions.isEmpty()) {
                return new QueryStatusResult(null, "PENDING");
            }

            String normalizedSearch = normalize(search);
            for (JsonNode tx : transactions) {
                if (isIncoming(tx) && isExpectedAccount(tx) && contentMatches(tx, normalizedSearch)) {
                    if (!amountMatches(tx, expectedAmount)) {
                        log.warn(
                                "RealSePay.queryStatus amount mismatch transferContent={} expected={} actual={}",
                                search,
                                expectedAmount,
                                amountIn(tx));
                        continue;
                    }
                    String sepayTxId = transactionId(tx);
                    log.info(
                            "RealSePay.queryStatus MATCH sepayTxId={} amountIn={} content={}",
                            sepayTxId,
                            amountIn(tx),
                            text(tx, "transaction_content"));
                    return new QueryStatusResult(sepayTxId, "SUCCESS");
                }
            }
            return new QueryStatusResult(null, "PENDING");
        } catch (RestClientException e) {
            log.warn("RealSePay.queryStatus HTTP error transferContent={} err={}", search, e.toString());
            throw new PaymentGatewayException("SePay API error: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.warn("RealSePay.queryStatus parse/config error transferContent={} err={}", search, e.toString());
            throw new PaymentGatewayException("SePay API error: " + e.getMessage(), e);
        }
    }

    @Override
    public RefundResult refund(String gatewayTransactionId, long amount, String refundRequestId) {
        log.warn(
                "RealSePay.refund manual refund required gatewayTransactionId={} amount={} refundRequestId={}",
                gatewayTransactionId,
                amount,
                refundRequestId);
        return new RefundResult("REJECTED", null, "vietqr_manual_refund_required");
    }

    private static RestClient buildRestClient(
            String baseUrl,
            Duration connectTimeout,
            Duration readTimeout,
            RestClient.Builder restClientBuilder) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return restClientBuilder.baseUrl(stripTrailingSlash(baseUrl))
                .requestFactory(requestFactory)
                .build();
    }

    private String buildTransferContent(UUID orderId) {
        String shortId = orderId.toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        return "TKF" + shortId;
    }

    private String buildQrImageUrl(long amount, String transferContent) {
        String bankName = BANK_NAME_MAP.getOrDefault(bankCode.toUpperCase(Locale.ROOT), bankCode);
        return qrBaseUrl
                + "?acc=" + enc(accountNumber)
                + "&bank=" + enc(bankName)
                + "&amount=" + amount
                + "&des=" + enc(transferContent)
                + "&template=" + enc(qrTemplate)
                + "&showinfo=true"
                + "&holder=" + enc(accountName);
    }

    private void validateQrConfig() {
        if (accountNumber.isBlank() || bankCode.isBlank() || qrBaseUrl.isBlank()) {
            throw new PaymentGatewayException("SePay QR configuration is incomplete");
        }
    }

    private JsonNode extractTransactions(JsonNode root) {
        if (root == null || root.isNull()) {
            return objectMapper.createArrayNode();
        }

        JsonNode oldShape = root.path("transactions");
        if (oldShape.isArray()) {
            return oldShape;
        }

        JsonNode status = root.path("status");
        boolean successStatus = (status.isTextual() && "success".equalsIgnoreCase(status.asText()))
                || (status.isNumber() && status.asInt() == 200);
        JsonNode newShape = root.path("data");
        if (successStatus && newShape.isArray()) {
            return newShape;
        }
        throw new PaymentGatewayException("Unexpected SePay transactions response");
    }

    private boolean isIncoming(JsonNode tx) {
        String transferType = text(tx, "transfer_type");
        if (!transferType.isBlank()) {
            return "in".equalsIgnoreCase(transferType);
        }
        return amountIn(tx) > 0;
    }

    private boolean isExpectedAccount(JsonNode tx) {
        String txAccountNumber = text(tx, "account_number");
        if (txAccountNumber.isBlank() || accountNumber.isBlank() || txAccountNumber.equals(accountNumber)) {
            return true;
        }
        String content = normalize(text(tx, "transaction_content"));
        String configuredAccount = normalize(accountNumber);
        if (content.contains(configuredAccount)) {
            return true;
        }
        return configuredAccount.startsWith("VQR") && content.contains(configuredAccount.substring(3));
    }

    private boolean contentMatches(JsonNode tx, String normalizedSearch) {
        String content = normalize(text(tx, "transaction_content"));
        return !content.isBlank()
                && (content.contains(normalizedSearch) || normalizedSearch.contains(content));
    }

    private long amountIn(JsonNode tx) {
        JsonNode amount = tx.path("amount_in");
        if (amount.isNumber()) {
            return amount.asLong();
        }
        if (amount.isTextual()) {
            try {
                return new BigDecimal(amount.asText().trim()).longValue();
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private boolean amountMatches(JsonNode tx, Long expectedAmount) {
        return expectedAmount == null || amountIn(tx) == expectedAmount;
    }

    private String transactionId(JsonNode tx) {
        String id = text(tx, "id");
        if (!id.isBlank()) {
            return id;
        }
        String referenceNumber = text(tx, "reference_number");
        return referenceNumber.isBlank() ? null : referenceNumber;
    }

    private static String normalize(String value) {
        return nullToBlank(value).toUpperCase(Locale.ROOT).replaceAll("[\\s\\-_.]", "");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String enc(String value) {
        return URLEncoder.encode(nullToBlank(value), StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String value) {
        String text = nullToBlank(value).trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
