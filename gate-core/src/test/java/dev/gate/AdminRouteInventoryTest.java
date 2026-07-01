package dev.gate;

import dev.gate.mapping.DeleteMapping;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PatchMapping;
import dev.gate.mapping.PostMapping;
import dev.gate.mapping.PutMapping;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AdminController のルート一覧（HTTP メソッド + パス）を固定して回帰検知する。
 * 将来 AdminController が複数コントローラーに分割されても、
 * ルートアノテーションの取りこぼし・タイポを防ぐためのセーフティネット。
 *
 * Router/Gate/DB には一切触れず、純粋なリフレクションのみで完結するため
 * 素の JVM テストとして実行できる。
 */
class AdminRouteInventoryTest {

    // 分割リファクタリングが進むにつれてこのリストにクラスが増えていく想定。
    // EXPECTED_ROUTES はそのリファクタリング中も変化してはならない
    // （= ルートの総和が保たれていることを保証する）。
    private static final List<Class<?>> ADMIN_CONTROLLERS = List.of(AdminController.class);

    // AdminController.java から grep で洗い出した現行の全ルート一覧（27件）。
    // 内訳: instances 6, cache/debug 2, tables 5, ddl 2, sql 1, stats 2, push 1, stars 5, yaml 3
    private static final Set<String> EXPECTED_ROUTES = Set.of(
        // instances (6)
        "GET /admin/instances",
        "GET /admin/instances/metrics",
        "POST /admin/instances/{id}/command",
        "GET /admin/instances/{id}/command/{requestId}",
        "GET /admin/instances/{id}/metrics",
        "DELETE /admin/instances/{id}",
        // cache/debug (2)
        "POST /admin/cache/clear",
        "GET /admin/debug/503",
        // tables (5)
        "GET /admin/tables",
        "GET /admin/tables/{table}",
        "PUT /admin/tables/{table}/{pk}",
        "DELETE /admin/tables/{table}/{pk}",
        "POST /admin/tables/{table}",
        // ddl (2)
        "POST /admin/ddl/tables",
        "POST /admin/ddl/tables/{table}/columns",
        // sql (1)
        "POST /admin/sql",
        // stats (2)
        "GET /admin/stats",
        "GET /admin/stats/daily",
        // push (1)
        "POST /admin/push/send",
        // stars (5)
        "GET /admin/stars/status",
        "POST /admin/stars/enabled",
        "POST /admin/stars/block",
        "DELETE /admin/stars/block",
        "GET /admin/stars/ranking",
        // yaml (3)
        "GET /admin/yaml/routes",
        "PUT /admin/yaml/routes",
        "GET /admin/yaml/status"
    );

    /** クラスの public インスタンスメソッドから @XxxMapping アノテーションのルート文字列を収集する。 */
    private static Set<String> collectRoutes(Class<?> clazz) {
        Set<String> routes = new HashSet<>();
        for (Method method : clazz.getMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (method.getDeclaringClass() == Object.class) continue;

            if (method.isAnnotationPresent(GetMapping.class)) {
                routes.add("GET " + method.getAnnotation(GetMapping.class).value());
            }
            if (method.isAnnotationPresent(PostMapping.class)) {
                routes.add("POST " + method.getAnnotation(PostMapping.class).value());
            }
            if (method.isAnnotationPresent(PutMapping.class)) {
                routes.add("PUT " + method.getAnnotation(PutMapping.class).value());
            }
            if (method.isAnnotationPresent(DeleteMapping.class)) {
                routes.add("DELETE " + method.getAnnotation(DeleteMapping.class).value());
            }
            if (method.isAnnotationPresent(PatchMapping.class)) {
                routes.add("PATCH " + method.getAnnotation(PatchMapping.class).value());
            }
        }
        return routes;
    }

    @Test
    void adminRouteInventoryMatchesExpected() {
        Set<String> actual = new HashSet<>();
        for (Class<?> controller : ADMIN_CONTROLLERS) {
            actual.addAll(collectRoutes(controller));
        }

        Set<String> missing    = new TreeSet<>(EXPECTED_ROUTES);
        missing.removeAll(actual);
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(EXPECTED_ROUTES);

        assertTrue(missing.isEmpty() && unexpected.isEmpty(),
            "AdminController のルート一覧が EXPECTED_ROUTES と一致しません。\n"
            + "missing (EXPECTED にあるが実際に無い): " + missing + "\n"
            + "unexpected (実際にあるが EXPECTED に無い): " + unexpected);
    }
}
