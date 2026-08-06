package cn.watsontech.snapagent.standalone;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = true)
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldAllowSettingsHtmlWithoutAuth() throws Exception {
        mockMvc.perform(get("/settings.html"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowSettingsApiWithoutAuth() throws Exception {
        mockMvc.perform(get("/snap-agent/settings"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowActuatorHealth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
