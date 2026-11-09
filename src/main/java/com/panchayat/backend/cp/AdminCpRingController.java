package com.panchayat.backend.cp;

import com.panchayat.backend.admin.media.MediaUrls;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Charm for each ring, set in the admin panel (rings themselves are Shop products). */
@RestController
@RequestMapping("/api/v1/admin/manage/cp-rings")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MODERATOR')")
public class AdminCpRingController {

    private final JdbcTemplate jdbc;
    private final CpService cp;

    public AdminCpRingController(JdbcTemplate jdbc, CpService cp) {
        this.jdbc = jdbc;
        this.cp = cp;
    }

    public record CharmRequest(Long charm, Long cpCharm) {
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        cp.syncShopRingsNow();
        return jdbc.query("select id, name, price, image_url, charm, cp_charm, active from cp_rings order by active desc, sort_order, price", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("name", rs.getString("name"));
            m.put("price", rs.getLong("price"));
            m.put("imageUrl", MediaUrls.link(rs.getString("image_url")));
            m.put("charm", rs.getObject("charm"));
            m.put("cpCharm", rs.getObject("cp_charm"));
            m.put("active", rs.getBoolean("active"));
            return m;
        });
    }

    /** charm empty = price / 5; cpCharm empty or 0 = the couple gets none from this ring. */
    @PutMapping("/{id}")
    public Map<String, Object> save(@PathVariable String id, @RequestBody CharmRequest b) {
        Long charm = b == null ? null : b.charm();
        Long cpCharm = b == null ? null : b.cpCharm();
        if ((charm != null && (charm < 0 || charm > 1_000_000_000L)) || (cpCharm != null && (cpCharm < 0 || cpCharm > 1_000_000_000L)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Charm must be 0 or more.");
        int n = jdbc.update("update cp_rings set charm = ?, cp_charm = ? where id = ?", charm, cpCharm, id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That ring doesn't exist.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("charm", charm);
        out.put("cpCharm", cpCharm);
        return out;
    }
}
