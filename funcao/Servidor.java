import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
 
public class Servidor {
 
    private static final int PORTA = 8081;
    private static final int TAMANHO_MINIMO_SENHA = 8;
    private static final Path PASTA_HTML = Path.of("public/html").toAbsolutePath().normalize();
 
    // sessões ativas: token do cookie -> e-mail do usuário logado
    private static final Map<String, String> SESSOES = new ConcurrentHashMap<>();
 
    // páginas que exigem login
    private static final String[] PAGINAS_PROTEGIDAS = { "/home.html" };
 
    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORTA), 0);
 
        // seguro(...) imprime no terminal qualquer erro inesperado de uma rota
        server.createContext("/", seguro(Servidor::tratarArquivos));
        server.createContext("/api/login", seguro(Servidor::tratarLogin));
        server.createContext("/api/cadastrar", seguro(Servidor::tratarCadastro));
        server.createContext("/api/car/new", seguro(Servidor::newCar));
        server.createContext("/api/car/delete", seguro(Servidor::deleteCar));
        server.createContext("/api/car/search", seguro(Servidor::buscarCarros));
        server.createContext("/api/car/update", seguro(Servidor::updateCar));
        server.createContext("/api/logout", seguro(Servidor::tratarLogout));
        server.createContext("/api/me", seguro(Servidor::tratarMe));
 
        server.start();
        System.out.println("Servidor rodando em http://localhost:" + PORTA);
        System.out.println("Para parar: Ctrl+C");
    }
 
    // Captura qualquer erro do handler, imprime no terminal e responde 500
    private static HttpHandler seguro(HttpHandler h) {
        return ex -> {
            try {
                h.handle(ex);
            } catch (Throwable t) {
                t.printStackTrace();
                try {
                    enviar(ex, 500, "Erro interno do servidor");
                } catch (Exception ignorado) {
                    ex.close();
                }
            }
        };
    }
 
    // ---------------------------------------------------------
    // ARQUIVOS ESTÁTICOS (HTML, CSS, JS da pasta public/html)
    // ---------------------------------------------------------
    private static void tratarArquivos(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            enviar(ex, 405, "Método não permitido");
            return;
        }
 
        String caminho = ex.getRequestURI().getPath();
        if (caminho.equals("/")) {
            caminho = "/login.html";
        }
 
        // bloqueia páginas protegidas para quem não está logado
        for (String protegida : PAGINAS_PROTEGIDAS) {
            if (caminho.equals(protegida) && emailLogado(ex) == null) {
                redirecionar(ex, "/login.html", null);
                return;
            }
        }
 
        Path arquivo = PASTA_HTML.resolve(caminho.substring(1)).normalize();
 
        // impede acessar arquivos fora da pasta (ex.: ../../config)
        if (!arquivo.startsWith(PASTA_HTML) || !Files.isRegularFile(arquivo)) {
            enviar(ex, 404, "Página não encontrada");
            return;
        }
 
        String nome = arquivo.getFileName().toString();
        String tipo = "application/octet-stream";
        if (nome.endsWith(".html")) tipo = "text/html; charset=utf-8";
        else if (nome.endsWith(".css")) tipo = "text/css; charset=utf-8";
        else if (nome.endsWith(".js")) tipo = "application/javascript; charset=utf-8";
 
        enviarBytes(ex, 200, tipo, Files.readAllBytes(arquivo));
    }
 
    // ---------------------------------------------------------
    // LOGIN  (POST /api/login  com campos: email, senha)
    // ---------------------------------------------------------
    private static void tratarLogin(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            enviar(ex, 405, "Use POST");
            return;
        }
 
        Map<String, String> campos = lerFormulario(ex);
        String email = campos.getOrDefault("email", "").trim().toLowerCase();
        String senha = campos.getOrDefault("senha", "");
 
        JdbcConnection db = new JdbcConnection();
        try {
            Connection con = db.getConnection();
            if (con == null) {
                redirecionar(ex, "/login.html?erro=banco", null);
                return;
            }
 
            Autenticacao auth = new Autenticacao(con);
 
            if (auth.login(email, senha)) {
                String token = gerarToken();
                SESSOES.put(token, email);
                redirecionar(ex, "/home.html",
                        "sessao=" + token + "; Path=/; HttpOnly; SameSite=Lax");
            } else {
                redirecionar(ex, "/login.html?erro=login", null);
            }
        } finally {
            db.closeConnection();
        }
    }
 
    // ---------------------------------------------------------
    // CADASTRO DE USUÁRIO  (POST /api/cadastrar
    //            campos: nome, email, senha, confirmar_senha)
    // ---------------------------------------------------------
    private static void tratarCadastro(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            enviar(ex, 405, "Use POST");
            return;
        }
 
        Map<String, String> campos = lerFormulario(ex);
        String nome = campos.getOrDefault("nome", "");
        String email = campos.getOrDefault("email", "");
        String senha = campos.getOrDefault("senha", "");
        String confirmar = campos.getOrDefault("confirmar_senha", "");
 
        // validações que o HTML sozinho não garante
        if (senha.length() < TAMANHO_MINIMO_SENHA) {
            redirecionar(ex, "/login.html?aba=cadastro&erro=senha-curta", null);
            return;
        }
        if (!senha.equals(confirmar)) {
            redirecionar(ex, "/login.html?aba=cadastro&erro=senhas", null);
            return;
        }
 
        JdbcConnection db = new JdbcConnection();
        try {
            Connection con = db.getConnection();
            if (con == null) {
                redirecionar(ex, "/login.html?aba=cadastro&erro=banco", null);
                return;
            }
 
            Autenticacao auth = new Autenticacao(con);
 
            if (auth.cadastrar(nome, email, senha)) {
                redirecionar(ex, "/login.html?ok=cadastro", null);
            } else {
                redirecionar(ex, "/login.html?aba=cadastro&erro=cadastro", null);
            }
        } finally {
            db.closeConnection();
        }
    }
 
    // =========================================================
    // CARROS
    // =========================================================
 
    // Dados de um carro já validados
    private static class Carro {
        String placa, chassi, marca, cor, combustivel, motor;
        int ano, capacidade, velocidade;
    }
 
    // Lê e valida o formulário de carro (usado no cadastro e na atualização).
    // Campos do formulário: placa, chassi, marca, ano, cor, combustivel,
    // pessoas, potencia, bateria, velocidade.
    // Devolve null se algum dado for inválido.
    private static Carro lerCarro(Map<String, String> c) {
        try {
            Carro car = new Carro();
            car.placa = c.getOrDefault("placa", "").trim().toUpperCase();
            car.chassi = c.getOrDefault("chassi", "").trim();     // no banco é VARCHAR
            car.marca = c.getOrDefault("marca", "").trim();
            car.cor = c.getOrDefault("cor", "").trim();
            car.combustivel = c.getOrDefault("combustivel", "").trim().toLowerCase();
            car.ano = Integer.parseInt(c.getOrDefault("ano", "").trim());
            car.capacidade = Integer.parseInt(c.getOrDefault("pessoas", "").trim());
            car.velocidade = Integer.parseInt(c.getOrDefault("velocidade", "").trim());
 
            int potencia = Integer.parseInt(c.getOrDefault("potencia", "").trim());
            String batTxt = c.getOrDefault("bateria", "").trim().replace(",", ".");
            double bateria = batTxt.isEmpty() ? 0 : Double.parseDouble(batTxt);
            if (Double.isNaN(bateria) || Double.isInfinite(bateria)) return null;
 
            boolean comBateria = car.combustivel.equals("hibrido") || car.combustivel.equals("eletrico");
            if (comBateria && bateria <= 0) return null;   // híbrido/elétrico precisa de bateria
            if (!comBateria) bateria = 0;                   // gasolina não tem bateria
 
            // o banco guarda potência e bateria juntas na coluna "motor"
            car.motor = potencia + "Cv" + (bateria > 0 ? " " + formatarNumero(bateria) + "kWh" : "");
 
            boolean valido =
                    car.placa.matches("[A-Z]{3}[0-9][A-Z][0-9]{2}")
                 && car.chassi.matches("[0-9]{17}")
                 && !car.marca.isEmpty() && car.marca.length() <= 50
                 && !car.cor.isEmpty() && car.cor.length() <= 50
                 && (car.combustivel.equals("gasolina") || comBateria)
                 && car.ano >= 1900 && car.ano <= 2027
                 && car.capacidade >= 1 && car.capacidade <= 9
                 && car.velocidade >= 1 && car.velocidade <= 500
                 && potencia >= 1 && potencia <= 3000
                 && bateria <= 1000;
 
            return valido ? car : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
 
    private static void preencher(PreparedStatement ps, Carro car) throws SQLException {
        ps.setString(1, car.placa);
        ps.setString(2, car.chassi);
        ps.setString(3, car.marca);
        ps.setInt(4, car.ano);
        ps.setString(5, car.cor);
        ps.setString(6, car.combustivel);
        ps.setInt(7, car.capacidade);
        ps.setString(8, car.motor);
        ps.setInt(9, car.velocidade);
    }
 
    // ---------------------------------------------------------
    // CREATE  (POST /api/car/new)
    // ---------------------------------------------------------
    private static void newCar(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            enviar(ex, 405, "Use POST");
            return;
        }
        if (emailLogado(ex) == null) {
            enviar(ex, 401, "Não logado");
            return;
        }
 
        Carro car = lerCarro(lerFormulario(ex));
        if (car == null) {
            enviar(ex, 400, "Dados inválidos");
            return;
        }
 
        String sql = "INSERT INTO carros "
                + "(placa, num_chassi, marca, ano, cor, combustivel, capacidade, motor, velocidade) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
 
        JdbcConnection db = new JdbcConnection();
        try {
            Connection con = db.getConnection();
            if (con == null) {
                enviar(ex, 500, "Sem conexão com o banco");
                return;
            }
 
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                preencher(ps, car);
                ps.executeUpdate();
                enviar(ex, 200, "OK");
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {   // placa ou chassi já cadastrados (UNIQUE)
                enviar(ex, 409, "Placa ou chassi já cadastrados");
            } else {
                e.printStackTrace();
                enviar(ex, 500, "Erro no banco");
            }
        } finally {
            db.closeConnection();
        }
    }
 
    // ---------------------------------------------------------
    // READ  (GET /api/car/search?placa=...&marca=...)
    // Sem filtros devolve todos os carros. Só filtra o que foi preenchido.
    // ---------------------------------------------------------
    private static void buscarCarros(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            enviar(ex, 405, "Use GET");
            return;
        }
        if (emailLogado(ex) == null) {
            enviar(ex, 401, "Não logado");
            return;
        }
 
        Map<String, String> f = lerQuery(ex);
        StringBuilder sql = new StringBuilder(
                "SELECT id_carros, num_chassi, placa, marca, ano, cor, combustivel, capacidade, motor, velocidade "
              + "FROM carros WHERE 1=1");
        List<Object> params = new ArrayList<>();
        Double potenciaMin = null;
        Double bateriaMin = null;
 
        try {
            // texto: contém o que foi digitado
            adicionarContem(sql, params, "placa", f.getOrDefault("placa", ""));
            adicionarContem(sql, params, "num_chassi", f.getOrDefault("chassi", ""));
 
            // texto: igual
            adicionarIgual(sql, params, "marca", f.getOrDefault("marca", ""));
            adicionarIgual(sql, params, "cor", f.getOrDefault("cor", ""));
            adicionarIgual(sql, params, "combustivel", f.getOrDefault("combustivel", ""));
 
            // números exatos
            Double ano = numero(f, "ano");
            if (ano != null) { sql.append(" AND ano = ?"); params.add(ano.intValue()); }
 
            Double pessoas = numero(f, "pessoas");
            if (pessoas != null) { sql.append(" AND capacidade = ?"); params.add(pessoas.intValue()); }
 
            // "ou mais"
            Double velocidade = numero(f, "velocidade");
            if (velocidade != null) { sql.append(" AND velocidade >= ?"); params.add(velocidade.intValue()); }
 
            // potência e bateria ficam dentro do texto da coluna "motor"
            // (ex.: "150Cv 60kWh"), então são filtradas depois, em Java
            potenciaMin = numero(f, "potencia");
            bateriaMin = numero(f, "bateria");
        } catch (NumberFormatException e) {
            enviar(ex, 400, "Número inválido");
            return;
        }
 
        sql.append(" ORDER BY num_chassi LIMIT 500");
 
        JdbcConnection db = new JdbcConnection();
        try {
            Connection con = db.getConnection();
            if (con == null) {
                enviar(ex, 500, "Erro de conexão com o banco");
                return;
            }
 
            try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
 
                StringBuilder json = new StringBuilder("[");
                try (ResultSet rs = ps.executeQuery()) {
                    int total = 0;
                    while (rs.next()) {
                        String motor = rs.getString("motor");
                        Double potencia = extrairNumero(motor, "cv");
                        Double bateria = extrairNumero(motor, "kwh");
 
                        if (potenciaMin != null && (potencia == null || potencia < potenciaMin)) continue;
                        if (bateriaMin != null && (bateria == null || bateria < bateriaMin)) continue;
 
                        if (total++ > 0) json.append(",");
                        json.append("{")
                            .append("\"id\":").append(rs.getInt("id_carros")).append(",")
                            .append("\"chassi\":").append(js(rs.getString("num_chassi"))).append(",")
                            .append("\"placa\":").append(js(rs.getString("placa"))).append(",")
                            .append("\"marca\":").append(js(rs.getString("marca"))).append(",")
                            .append("\"ano\":").append(rs.getInt("ano")).append(",")
                            .append("\"cor\":").append(js(rs.getString("cor"))).append(",")
                            .append("\"combustivel\":").append(js(rs.getString("combustivel"))).append(",")
                            .append("\"pessoas\":").append(rs.getInt("capacidade")).append(",")
                            .append("\"motor\":").append(js(motor)).append(",")
                            .append("\"potencia\":").append(numJson(potencia)).append(",")
                            .append("\"bateria\":").append(numJson(bateria)).append(",")
                            .append("\"velocidade\":").append(rs.getInt("velocidade"))
                            .append("}");
                    }
                }
                json.append("]");
 
                enviarBytes(ex, 200, "application/json; charset=utf-8",
                        json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            enviar(ex, 500, "Erro ao consultar o banco");
        } finally {
            db.closeConnection();
        }
    }
 
    // ---------------------------------------------------------
    // UPDATE  (POST /api/car/update  com id + os campos do carro)
    // ---------------------------------------------------------
    private static void updateCar(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            enviar(ex, 405, "Use POST");
            return;
        }
        if (emailLogado(ex) == null) {
            enviar(ex, 401, "Não logado");
            return;
        }
 
        Map<String, String> campos = lerFormulario(ex);
 
        int id;
        try {
            id = Integer.parseInt(campos.getOrDefault("id", "").trim());
        } catch (NumberFormatException e) {
            enviar(ex, 400, "Id inválido");
            return;
        }
 
        Carro car = lerCarro(campos);
        if (car == null) {
            enviar(ex, 400, "Dados inválidos");
            return;
        }
 
        String sql = "UPDATE carros SET placa = ?, num_chassi = ?, marca = ?, ano = ?, cor = ?, "
                + "combustivel = ?, capacidade = ?, motor = ?, velocidade = ? WHERE id_carros = ?";
 
        JdbcConnection db = new JdbcConnection();
        try {
            Connection con = db.getConnection();
            if (con == null) {
                enviar(ex, 500, "Erro de conexão com o banco");
                return;
            }
 
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                preencher(ps, car);
                ps.setInt(10, id);
 
                if (ps.executeUpdate() == 0) {
                    enviar(ex, 404, "Carro não encontrado");
                } else {
                    enviar(ex, 200, "OK");
                }
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {   // placa ou chassi já existem em outro carro
                enviar(ex, 409, "Placa ou chassi já cadastrados");
            } else {
                e.printStackTrace();
                enviar(ex, 500, "Erro ao atualizar no banco");
            }
        } finally {
            db.closeConnection();
        }
    }
 
    // ---------------------------------------------------------
    // DELETE  (POST /api/car/delete  com o campo "id" OU "placa")
    // ---------------------------------------------------------
    private static void deleteCar(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            enviar(ex, 405, "Use POST");
            return;
        }
        if (emailLogado(ex) == null) {
            enviar(ex, 401, "Não logado");
            return;
        }
 
        Map<String, String> campos = lerFormulario(ex);
        String idTxt = campos.getOrDefault("id", "").trim();
        String placa = campos.getOrDefault("placa", "").trim().toUpperCase();
 
        String sql;
        Object valor;
        if (!idTxt.isEmpty()) {
            try {
                valor = Integer.valueOf(idTxt);
            } catch (NumberFormatException e) {
                enviar(ex, 400, "Id inválido");
                return;
            }
            sql = "DELETE FROM carros WHERE id_carros = ?";
        } else if (!placa.isEmpty()) {
            valor = placa;
            sql = "DELETE FROM carros WHERE placa = ?";
        } else {
            enviar(ex, 400, "Informe o id ou a placa do carro");
            return;
        }
 
        JdbcConnection db = new JdbcConnection();
        try {
            Connection con = db.getConnection();
            if (con == null) {
                enviar(ex, 500, "Sem conexão com o banco");
                return;
            }
 
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setObject(1, valor);
                if (ps.executeUpdate() == 0) {
                    enviar(ex, 404, "Carro não encontrado");
                } else {
                    enviar(ex, 200, "OK");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            enviar(ex, 500, "Erro ao deletar no banco");
        } finally {
            db.closeConnection();
        }
    }
 
    // ----- auxiliares da consulta -----
 
    private static void adicionarContem(StringBuilder sql, List<Object> params, String coluna, String valor) {
        valor = valor.trim().replaceAll("[%_\\\\]", "");
        if (valor.isEmpty()) return;
        sql.append(" AND LOWER(").append(coluna).append(") LIKE LOWER(?)");   // "coluna" é fixa no código
        params.add("%" + valor + "%");
    }
 
    private static void adicionarIgual(StringBuilder sql, List<Object> params, String coluna, String valor) {
        valor = valor.trim();
        if (valor.isEmpty()) return;
        sql.append(" AND LOWER(").append(coluna).append(") = LOWER(?)");       // "coluna" é fixa no código
        params.add(valor);
    }
 
    private static Map<String, String> lerQuery(HttpExchange ex) {
        Map<String, String> campos = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return campos;
 
        for (String par : q.split("&")) {
            if (par.isEmpty()) continue;
            String[] kv = par.split("=", 2);
            String chave = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String valor = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            campos.put(chave, valor);
        }
        return campos;
    }
 
    private static Double numero(Map<String, String> f, String nome) {
        String v = f.getOrDefault(nome, "").trim().replace(",", ".");
        return v.isEmpty() ? null : Double.valueOf(v);   // pode lançar NumberFormatException
    }
 
    // acha o número que vem antes da unidade no texto do motor ("150Cv 60kWh")
    private static Double extrairNumero(String motor, String unidade) {
        if (motor == null) return null;
        Matcher m = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*" + unidade, Pattern.CASE_INSENSITIVE).matcher(motor);
        if (!m.find()) return null;
        return Double.valueOf(m.group(1).replace(',', '.'));
    }
 
    private static String formatarNumero(double d) {
        return d % 1 == 0 ? String.valueOf((long) d) : String.valueOf(d);
    }
 
    private static String numJson(Double d) {
        return d == null ? "null" : formatarNumero(d);
    }
 
    // transforma texto em string JSON (com aspas e escapes)
    private static String js(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
 
    // ---------------------------------------------------------
    // LOGOUT  (GET /api/logout)
    // ---------------------------------------------------------
    private static void tratarLogout(HttpExchange ex) throws IOException {
        String token = lerCookie(ex, "sessao");
        if (token != null) {
            SESSOES.remove(token);
        }
        redirecionar(ex, "/login.html",
                "sessao=; Path=/; HttpOnly; Max-Age=0");
    }
 
    // ---------------------------------------------------------
    // QUEM ESTÁ LOGADO  (GET /api/me  -> devolve o e-mail)
    // ---------------------------------------------------------
    private static void tratarMe(HttpExchange ex) throws IOException {
        String email = emailLogado(ex);
        if (email == null) {
            enviar(ex, 401, "Não logado");
        } else {
            enviar(ex, 200, email);
        }
    }
 
    // ---------------------------------------------------------
    // FUNÇÕES AUXILIARES
    // ---------------------------------------------------------
    private static String gerarToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
 
    private static String lerCookie(HttpExchange ex, String nome) {
        String cabecalho = ex.getRequestHeaders().getFirst("Cookie");
        if (cabecalho == null) return null;
 
        for (String parte : cabecalho.split(";")) {
            String[] kv = parte.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(nome)) {
                return kv[1];
            }
        }
        return null;
    }
 
    private static String emailLogado(HttpExchange ex) {
        String token = lerCookie(ex, "sessao");
        return token == null ? null : SESSOES.get(token);
    }
 
    private static Map<String, String> lerFormulario(HttpExchange ex) throws IOException {
        String corpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> campos = new HashMap<>();
 
        for (String par : corpo.split("&")) {
            if (par.isEmpty()) continue;
            String[] kv = par.split("=", 2);
            String chave = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String valor = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            campos.put(chave, valor);
        }
        return campos;
    }
 
    private static void enviar(HttpExchange ex, int status, String texto) throws IOException {
        enviarBytes(ex, status, "text/plain; charset=utf-8", texto.getBytes(StandardCharsets.UTF_8));
    }
 
    private static void enviarBytes(HttpExchange ex, int status, String tipo, byte[] dados) throws IOException {
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.sendResponseHeaders(status, dados.length);
        ex.getResponseBody().write(dados);
        ex.close();
    }
 
    private static void redirecionar(HttpExchange ex, String destino, String cookie) throws IOException {
        ex.getResponseHeaders().set("Location", destino);
        if (cookie != null) {
            ex.getResponseHeaders().add("Set-Cookie", cookie);
        }
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }
}
 