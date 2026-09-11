package com.bigdata.scriptbox;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSmokeTest extends BaseIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired TenantService tenantService;

    private static final ParameterizedTypeReference<ApiResponse<Tenant>> TENANT_TYPE =
            new ParameterizedTypeReference<ApiResponse<Tenant>>() {};

    @Test
    void tenantsApiRoundTrip() {
        String base = "http://localhost:" + port;
        // create
        Tenant t = new Tenant();
        t.setName("api-tenant");
        t.setPrincipal("api@EXAMPLE.COM");
        t.setDefaultDatabase("default");
        t.setEnabled(true);
        ResponseEntity<ApiResponse<Tenant>> created = rest.exchange(
                base + "/api/tenants", HttpMethod.POST,
                HttpEntity.EMPTY, TENANT_TYPE);  // ignored, post below
        // Use postForObject pattern via exchange:
        HttpHeaders jh = new HttpHeaders();
        jh.setContentType(MediaType.APPLICATION_JSON);
        created = rest.exchange(base + "/api/tenants", HttpMethod.POST,
                new HttpEntity<>(t, jh), TENANT_TYPE);
        assertEquals(0, created.getBody().getCode());
        Long id = created.getBody().getData().getId();

        // fetch
        ResponseEntity<ApiResponse<Tenant>> fetched = rest.exchange(
                base + "/api/tenants/" + id, HttpMethod.GET, HttpEntity.EMPTY, TENANT_TYPE);
        assertEquals(0, fetched.getBody().getCode());
        assertEquals("api-tenant", fetched.getBody().getData().getName());

        // update
        Tenant upd = new Tenant();
        upd.setId(id);
        upd.setName("api-tenant-2");
        upd.setEnabled(false);
        upd.setPrincipal("api@EXAMPLE.COM");
        ResponseEntity<ApiResponse<Tenant>> updated = rest.exchange(
                base + "/api/tenants/" + id, HttpMethod.PUT,
                new HttpEntity<>(upd, jh), TENANT_TYPE);
        assertEquals(0, updated.getBody().getCode());
        assertEquals("api-tenant-2", updated.getBody().getData().getName());
        assertFalse(updated.getBody().getData().getEnabled());

        // delete
        ResponseEntity<ApiResponse<Void>> deleted = rest.exchange(
                base + "/api/tenants/" + id, HttpMethod.DELETE, HttpEntity.EMPTY,
                new ParameterizedTypeReference<ApiResponse<Void>>() {});
        assertEquals(0, deleted.getBody().getCode());

        assertNull(tenantService.getById(id));
    }
}