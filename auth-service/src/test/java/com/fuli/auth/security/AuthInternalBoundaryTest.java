package com.fuli.auth.security;

import com.fuli.auth.controller.AuthController;
import com.fuli.auth.service.IdempotentMessageService;
import com.fuli.auth.service.UserService;
import com.fuli.auth.util.JwtUtil;
import com.fuli.common.api.config.InternalKeyProperties;
import com.fuli.common.api.config.JwtProperties;
import com.fuli.common.api.feign.AuthFeignClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthInternalBoundaryTest {
    @Test
    void serviceCashContractRequiresSharedKeyAndWorksWithoutUserBearer() throws Exception {
        InternalKeyProperties internal = new InternalKeyProperties();
        internal.setInternalKey("auth-internal-test-0123456789");
        JwtProperties jwt = new JwtProperties();
        jwt.setSecret("auth-internal-jwt-012345678901234567890123456789");
        var controller = new AuthController(mock(UserService.class), mock(PasswordEncoder.class), mock(JwtUtil.class),
                new MockHttpServletRequest(), mock(IdempotentMessageService.class), internal, mock(ObjectProvider.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new InternalKeyFilter(internal), new JwtAuthenticationFilter(jwt)).build();
        var metadata = new SpringMvcContract().parseAndValidateMetadata(AuthFeignClient.class).stream()
                .filter(method -> method.configKey().contains("#getUserInitialCash(")).findFirst().orElseThrow();
        String path = metadata.template().path();
        mvc.perform(get(path).param("userId", "2").header("X-User-Id", "2"))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).param("userId", "2").header("X-Internal-Key", "wrong-key"))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).param("userId", "2").header("X-Internal-Key", internal.getInternalKey()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(200000.00));
    }
}
