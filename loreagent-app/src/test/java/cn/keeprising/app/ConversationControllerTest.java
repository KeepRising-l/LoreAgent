package cn.keeprising.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(InMemoryTestRepos.class)
class ConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldSupportCrudAndReturn404AfterDelete() throws Exception {
        String body = mockMvc.perform(post("/api/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"第一会话\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("第一会话"))
                .andReturn().getResponse().getContentAsString();

        String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));

        mockMvc.perform(patch("/api/conversations/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"改名后\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("改名后"));

        mockMvc.perform(get("/api/conversations/" + id + "/messages"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mockMvc.perform(delete("/api/conversations/" + id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/conversations/" + id + "/messages"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
