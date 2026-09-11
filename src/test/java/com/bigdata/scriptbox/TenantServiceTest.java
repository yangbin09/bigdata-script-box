package com.bigdata.scriptbox;

import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

class TenantServiceTest extends BaseIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Test
    void createAndListAndGet() {
        Tenant t = new Tenant();
        t.setName("alpha");
        t.setPrincipal("alpha@EXAMPLE.COM");
        t.setDefaultDatabase("db1");
        t.setDescription("first");
        t.setEnabled(true);
        Tenant saved = tenantService.create(t);
        assertNotNull(saved.getId());

        assertEquals(1, tenantService.listAll().size());
        Tenant fetched = tenantService.getById(saved.getId());
        assertEquals("alpha", fetched.getName());
        assertEquals("alpha@EXAMPLE.COM", fetched.getPrincipal());
    }

    @Test
    void updateAndDisable() {
        Tenant t = tenantService.create(build("beta"));
        Tenant upd = new Tenant();
        upd.setId(t.getId());
        upd.setName("beta2");
        upd.setEnabled(false);
        upd.setPrincipal(t.getPrincipal());
        upd.setDefaultDatabase(t.getDefaultDatabase());
        upd.setDescription("updated");
        Tenant after = tenantService.update(upd);
        assertEquals("beta2", after.getName());
        assertEquals(Boolean.FALSE, after.getEnabled());

        Tenant flipped = tenantService.setEnabled(t.getId(), true);
        assertEquals(Boolean.TRUE, flipped.getEnabled());
    }

    @Test
    void deleteRemovesTenant() {
        Tenant t = tenantService.create(build("gamma"));
        tenantService.delete(t.getId());
        assertNull(tenantService.getById(t.getId()));
        assertEquals(0, tenantService.listAll().size());
    }

    private Tenant build(String name) {
        Tenant t = new Tenant();
        t.setName(name);
        t.setPrincipal(name + "@EXAMPLE.COM");
        t.setDefaultDatabase("default");
        t.setDescription("d");
        t.setEnabled(true);
        return t;
    }
}