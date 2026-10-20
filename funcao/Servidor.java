import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Servidor {

    private static final int PORTA = 8080;
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