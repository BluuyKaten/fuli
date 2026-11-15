package com.fuli.data.okx;

import com.fuli.data.security.OkxAuthz;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OkxSuggestionControllerTest {

    private static final long USER = 7L;

    @Test
    void rejectedOrderIsReturnedAsBadRequest() throws Exception {
        OkxSuggestionService suggestionService = mock(OkxSuggestionService.class);
        OkxSuggestionExecutionService executionService = mock(OkxSuggestionExecutionService.class);
        OkxAuthz authz = mock(OkxAuthz.class);
        when(authz.requireWrite(any(HttpServletRequest.class), eq("executeSuggestion"),
                eq("suggestionId=100"))).thenReturn(com.fuli.data.security.OkxPrincipal.user(USER));
        when(executionService.execute(USER, 100L))
                .thenThrow(new OkxOrderRejectedException("51008", "余额不足"));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new OkxSuggestionController(suggestionService, executionService, authz)).build();

        mvc.perform(post("/data/okx/suggestions/100/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
