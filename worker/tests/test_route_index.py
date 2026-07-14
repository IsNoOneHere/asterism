from asterism_worker.activities.route_index import extract_route_candidates


def test_extracts_react_vue_spring_and_fastapi_routes(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "routes.tsx").write_text('''
        <Route path="/orders" element={<Orders />} />
        const title = "订单列表"
    ''', encoding="utf-8")
    (repo / "router.vue").write_text("const routes = [{ path: '/customers', component: Customers }]", encoding="utf-8")
    (repo / "OrderController.java").write_text('''
        @RequestMapping("/api/orders")
        class OrderController {
          @GetMapping("/{id}") Object detail() { return null; }
          @PostMapping("/{id}") Object update() { return null; }
        }
    ''', encoding="utf-8")
    (repo / "app.py").write_text('@app.post("/api/refunds")\ndef refund(): pass\n', encoding="utf-8")

    entries = extract_route_candidates(str(repo))

    routes = {entry["routePath"] for entry in entries}
    endpoints = {endpoint for entry in entries for endpoint in entry["apiEndpoints"]}
    assert {"/orders", "/customers", "/api/orders/{id}", "/api/refunds"} <= routes
    assert {"GET /api/orders/{id}", "POST /api/orders/{id}", "POST /api/refunds"} <= endpoints
    assert len([entry for entry in entries if entry["routePath"] == "/api/orders/{id}"]) == 1
    assert next(entry for entry in entries if entry["routePath"] == "/orders")["anchorTexts"] == ["orders", "/orders", "订单列表"]
