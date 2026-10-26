package dev.mayhur.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:api-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class ApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void reserveAndConfirmOverHttp() throws Exception {
        var body = """
                {"orderRef": "MP-00001234", "lines": [{"sku": "HB-JUICE-1L", "quantity": 5}]}
                """;
        var response = mvc.perform(post("/api/reservations").header("Idempotency-Key", "api-test-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.lines[0].warehouse").value("JHB"))
                .andReturn().getResponse().getContentAsString();
        var id = com.jayway.jsonpath.JsonPath.read(response, "$.id").toString();

        mvc.perform(post("/api/reservations/{id}/confirm", id)).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(get("/api/stock/HB-JUICE-1L")).andExpect(jsonPath("$[0].onHand").value(30));
    }

    @Test
    void notEnoughStockIs409WithTheReason() throws Exception {
        mvc.perform(post("/api/reservations").header("Idempotency-Key", "api-test-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderRef\": \"X\", \"lines\": [{\"sku\": \"HB-WATER-500\", \"quantity\": 999}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.problems[0]").value("HB-WATER-500: requested 999, only 900 available"));
    }

    @Test
    void idempotencyKeyIsRequiredAndInputIsValidated() throws Exception {
        mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderRef\": \"X\", \"lines\": [{\"sku\": \"A\", \"quantity\": 1}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/reservations").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderRef\": \"X\", \"lines\": [{\"sku\": \"A\", \"quantity\": 0}]}"))
                .andExpect(status().isBadRequest());
    }
}
