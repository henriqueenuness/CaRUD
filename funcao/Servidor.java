import java.sql.Connection;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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

        server.createContext("/", Servidor::tratarArquivos);
        server.createContext("/api/login", Servidor::tratarLogin);
        server.createContext("/api/cadastrar", Servidor::tratarCadastro);
        server.createContext("/api/car/new", Servidor::newCar);
        server.createContext("/api/car/delete", Servidor::deleteCar);
        server.createContext("/api/car/search", Servidor::buscarCarros);
        server.createContext("/api/logout", Servidor::tratarLogout);
        server.createContext("/api/me", Servidor::tratarMe);

        server.start();
        System.out.println("Servidor rodando em http://localhost:" + PORTA);
        System.out.println("Para parar: Ctrl+C");
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
    // CADASTRO  (POST /api/cadastrar
    //            campos: nome, email, senha, confirmar_senha)
    // ---------------------------------------------------------
    private static void newCar(HttpExchange ex) throws IOException {//os comentarios a seguir não são de IA, mas sim do imbecil do henrique tentando entender essa bomba de linguagem
           System.out.println("newCar chamado: " + ex.getRequestMethod());
        if (!ex.getRequestMethod().equals("POST")) {
            enviar(ex,405, "Use POST");
            return;
        }
        if (emailLogado(ex) == null) {
            enviar(ex, 401, "Não logado");
            return;
        }

        Map<String, String> campos = lerFormulario(ex);
        String placa       = campos.getOrDefault("placa", "").trim().toUpperCase();
        String chassi      = campos.getOrDefault("num_chassi", "").trim();
        String marca       = campos.getOrDefault("marca", "").trim();
        String cor         = campos.getOrDefault("cor", "").trim();
        String combustivel = campos.getOrDefault("combustivel", "").trim().toLowerCase();
        String motor       = campos.getOrDefault("motor", "").trim();

        int ano, capacidade, velocidade;
        try {
            ano = Integer.parseInt(campos.getOrDefault("ano", "").trim());
            capacidade = Integer.parseInt(campos.getOrDefault("capacidade", "").trim());
            velocidade = Integer.parseInt(campos.getOrDefault("velocidade", "").trim());
        } catch (NumberFormatException e) {
            redirecionar(ex, "/home.html?erro=dados#adicionar", null);
            return;
        }

        // validações que o HTML sozinho não garante
        boolean valido =
                placa.matches("[A-Z]{3}[0-9][A-Z][0-9]{2}")
            && chassi.matches("[0-9]{17}")
            && !marca.isEmpty() && marca.length() <= 50
            && !cor.isEmpty() && cor.length() <= 50
            && !motor.isEmpty() && motor.length() <= 100
            && (combustivel.equals("gasolina") || combustivel.equals("hibrido") || combustivel.equals("eletrico"))
            && ano >= 1900 && ano <= 2027
            && capacidade >= 1 && capacidade <= 9
            && velocidade >= 1 && velocidade <= 500;

        if (!valido) {
            redirecionar(ex, "/home.html?erro=dados#adicionar", null);
            return;
        }

        String sql = "INSERT INTO carros "
                + "(placa, num_chassi, marca, ano, cor, combustivel, capacidade, motor, velocidade) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        JdbcConnection db = new JdbcConnection();
        try {
            Connection con = db.getConnection();
            if (con == null) {
                redirecionar(ex, "/home.html?erro=banco#adicionar", null);
                return;
            }

            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, placa);
                ps.setString(2, chassi);
                ps.setString(3, marca);
                ps.setInt(4, ano);
                ps.setString(5, cor);
                ps.setString(6, combustivel);
                ps.setInt(7, capacidade);
                ps.setString(8, motor);
                ps.setInt(9, velocidade);
                ps.executeUpdate();
                redirecionar(ex, "/home.html?ok=carro", null);
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {   // placa ou chassi já cadastrados (UNIQUE)
                redirecionar(ex, "/home.html?erro=duplicado#adicionar", null);
            } else {
                e.printStackTrace();
                redirecionar(ex, "/home.html?erro=banco#adicionar", null);
            }
        } finally {
            db.closeConnection();
        }
    }
    private static void deleteCar(HttpExchange ex) throws IOException {}
    private static void adicionarContem(StringBuilder sql, List<Object> params, String coluna, String valor) {
    valor = valor.trim().replaceAll("[%_\\\\]", "");
    if (valor.isEmpty()) return;
    sql.append(" AND LOWER(").append(coluna).append(") LIKE LOWER(?)");   // "coluna" é fixa no código
    params.add("%" + valor + "%");
    }
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
    // READ (busca de carros) //
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
        "SELECT num_chassi, placa, marca, ano, cor, combustivel, capacidade, motor, velocidade "
      + "FROM carros WHERE 1=1");
    List<Object> params = new ArrayList<>();

    try {
        // texto: contém o que foi digitado (sem diferenciar maiúsculas/minúsculas)
        adicionarContem(sql, params, "placa", f.getOrDefault("placa", ""));
        adicionarContem(sql, params, "num_chassi", f.getOrDefault("num_chassi", ""));
        adicionarContem(sql, params, "cor", f.getOrDefault("cor", ""));
        adicionarContem(sql, params, "motor", f.getOrDefault("motor", ""));

        // texto: igual
        String marca = f.getOrDefault("marca", "").trim();
        if (!marca.isEmpty()) {
            sql.append(" AND LOWER(marca) = LOWER(?)");
            params.add(marca);
        }

        String comb = f.getOrDefault("combustivel", "").trim();
        if (!comb.isEmpty()) {
            sql.append(" AND LOWER(combustivel) = LOWER(?)");
            params.add(comb);
        }

        // números exatos
        Double ano = numero(f, "ano");
        if (ano != null) { sql.append(" AND ano = ?"); params.add(ano.intValue()); }

        Double capacidade = numero(f, "capacidade");
        if (capacidade != null) { sql.append(" AND capacidade = ?"); params.add(capacidade.intValue()); }

        // número "parecido": margem de 20% para mais ou para menos
        adicionarFaixa(sql, params, "velocidade", numero(f, "velocidade"));
    } catch (NumberFormatException e) {
        enviar(ex, 400, "Número inválido");
        return;
    }

    sql.append(" ORDER BY num_chassi LIMIT 100");

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
                boolean primeiro = true;
                while (rs.next()) {
                    if (!primeiro) json.append(",");
                    primeiro = false;

                    json.append("{")
                        .append("\"num_chassi\":").append(js(rs.getString("num_chassi"))).append(",")
                        .append("\"placa\":").append(js(rs.getString("placa"))).append(",")
                        .append("\"marca\":").append(js(rs.getString("marca"))).append(",")
                        .append("\"ano\":").append(rs.getInt("ano")).append(",")
                        .append("\"cor\":").append(js(rs.getString("cor"))).append(",")
                        .append("\"combustivel\":").append(js(rs.getString("combustivel"))).append(",")
                        .append("\"capacidade\":").append(rs.getInt("capacidade")).append(",")
                        .append("\"motor\":").append(js(rs.getString("motor"))).append(",")
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

// ----- auxiliares da consulta -----

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
    String v = f.getOrDefault(nome, "").trim();
    return v.isEmpty() ? null : Double.valueOf(v);   // pode lançar NumberFormatException
}

private static void adicionarFaixa(StringBuilder sql, List<Object> params, String coluna, Double valor) {
    if (valor == null) return;
    sql.append(" AND ").append(coluna).append(" BETWEEN ? AND ?");   // "coluna" é fixa no código, nunca vem do usuário
    params.add(valor * 0.8);
    params.add(valor * 1.2);
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