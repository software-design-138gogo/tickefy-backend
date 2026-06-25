package com.tickefy.checkin.modules.vip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.checkin.modules.vip.client.CsvVipClient;
import com.tickefy.checkin.modules.vip.dto.VipGuestDto;
import com.tickefy.checkin.modules.vip.exception.CsvUnavailableException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * AC coverage:
 * AC-CSV-1  forward-Bearer
 * AC-CSV-2  page-through (2 pages merged)
 * AC-CSV-3  envelope unwrap → VipGuestDto fields
 * AC-CSV-4  csv-down (503) → CsvUnavailableException
 * AC-CSV-5  no-context → IllegalStateException (NEVER calls HTTP)
 */
class CsvVipClientTest {

    private static final String BASE_URL = "http://csv-test-host";
    private static final UUID CONCERT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID TICKET_TYPE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private CsvVipClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        client = new CsvVipClient(restTemplate, objectMapper, BASE_URL);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // -------------------------------------------------------------------------
    // AC-CSV-1  forward-Bearer header
    // -------------------------------------------------------------------------

    @Test
    void fetchAll_shouldForwardBearerTokenFromRequestContext() {
        setRequestContext("Bearer xyz");

        mockServer.expect(requestTo(pageUrl(0)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer xyz"))
                .andRespond(withSuccess(singlePageJson(List.of(vipEntry("alice@example.com", "Alice"))), MediaType.APPLICATION_JSON));

        List<VipGuestDto> result = client.fetchAll(CONCERT_ID);

        mockServer.verify();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).email()).isEqualTo("alice@example.com");
    }

    // -------------------------------------------------------------------------
    // AC-CSV-2  page-through: 2 requests, results merged
    // -------------------------------------------------------------------------

    @Test
    void fetchAll_shouldPageThroughAndMergeAllResults() {
        setRequestContext("Bearer tok");

        String page0Json = """
                {
                  "success": true,
                  "data": {
                    "content": [
                      {"email":"a@x.com","fullName":"A","ticketTypeId":"%s","ticketTypeName":"VIP"},
                      {"email":"b@x.com","fullName":"B","ticketTypeId":"%s","ticketTypeName":"VIP"}
                    ],
                    "totalPages": 2,
                    "number": 0,
                    "size": 200
                  }
                }
                """.formatted(TICKET_TYPE_ID, TICKET_TYPE_ID);

        String page1Json = """
                {
                  "success": true,
                  "data": {
                    "content": [
                      {"email":"c@x.com","fullName":"C","ticketTypeId":"%s","ticketTypeName":"VIP"}
                    ],
                    "totalPages": 2,
                    "number": 1,
                    "size": 200
                  }
                }
                """.formatted(TICKET_TYPE_ID);

        mockServer.expect(requestTo(pageUrl(0))).andRespond(withSuccess(page0Json, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(pageUrl(1))).andRespond(withSuccess(page1Json, MediaType.APPLICATION_JSON));

        List<VipGuestDto> result = client.fetchAll(CONCERT_ID);

        mockServer.verify(); // asserts EXACTLY 2 requests
        assertThat(result).hasSize(3);
        assertThat(result).extracting(VipGuestDto::email)
                .containsExactlyInAnyOrder("a@x.com", "b@x.com", "c@x.com");
    }

    // -------------------------------------------------------------------------
    // AC-CSV-3  envelope unwrap → all VipGuestDto fields mapped correctly
    // -------------------------------------------------------------------------

    @Test
    void fetchAll_shouldUnwrapEnvelopeAndMapAllDtoFields() {
        setRequestContext("Bearer tok");

        String json = """
                {
                  "success": true,
                  "data": {
                    "content": [
                      {
                        "email": "bob@example.com",
                        "fullName": "Bob Smith",
                        "ticketTypeId": "%s",
                        "ticketTypeName": "Gold VIP"
                      }
                    ],
                    "totalPages": 1,
                    "number": 0,
                    "size": 200
                  }
                }
                """.formatted(TICKET_TYPE_ID);

        mockServer.expect(requestTo(pageUrl(0))).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<VipGuestDto> result = client.fetchAll(CONCERT_ID);

        assertThat(result).hasSize(1);
        VipGuestDto dto = result.get(0);
        assertThat(dto.email()).isEqualTo("bob@example.com");
        assertThat(dto.fullName()).isEqualTo("Bob Smith");
        assertThat(dto.ticketTypeId()).isEqualTo(TICKET_TYPE_ID);
        assertThat(dto.ticketTypeName()).isEqualTo("Gold VIP");
    }

    // -------------------------------------------------------------------------
    // AC-CSV-4  csv-down (5xx) → CsvUnavailableException
    // -------------------------------------------------------------------------

    @Test
    void fetchAll_whenCsvServiceReturns503_shouldThrowCsvUnavailableException() {
        setRequestContext("Bearer tok");

        mockServer.expect(requestTo(pageUrl(0))).andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchAll(CONCERT_ID))
                .isInstanceOf(CsvUnavailableException.class);

        mockServer.verify();
    }

    // -------------------------------------------------------------------------
    // AC-CSV-5  no-context → IllegalStateException, HTTP NEVER called
    // -------------------------------------------------------------------------

    @Test
    void fetchAll_withoutRequestContext_shouldThrowIllegalStateExceptionWithoutCallingHttp() {
        // No setRequestContext() call — RequestContextHolder is empty
        RequestContextHolder.resetRequestAttributes();

        assertThatThrownBy(() -> client.fetchAll(CONCERT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("request auth context");

        // mockServer.verify() would PASS (0 requests expected, 0 made)
        mockServer.verify();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void setRequestContext(String authHeader) {
        MockHttpServletRequest mockReq = new MockHttpServletRequest();
        mockReq.addHeader("Authorization", authHeader);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockReq));
    }

    private String pageUrl(int page) {
        return BASE_URL + "/internal/concerts/" + CONCERT_ID + "/vip-guests?page=" + page + "&size=200";
    }

    private String vipEntry(String email, String fullName) {
        return """
                {"email":"%s","fullName":"%s","ticketTypeId":"%s","ticketTypeName":"VIP"}
                """.formatted(email, fullName, TICKET_TYPE_ID).strip();
    }

    private String singlePageJson(List<String> entries) {
        String content = String.join(",", entries);
        return """
                {
                  "success": true,
                  "data": {
                    "content": [%s],
                    "totalPages": 1,
                    "number": 0,
                    "size": 200
                  }
                }
                """.formatted(content);
    }
}
