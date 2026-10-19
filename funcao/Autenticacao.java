import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class Autenticacao {

    private static final String TABELA = "usuarios";
    private static final String COL_NOME = "nome";
    private static final String COL_EMAIL = "email";
    private static final String COL_SENHA = "senha";

    private static final int ITERACOES = 120000;
    private static final int TAMANHO_CHAVE = 256;

    private final Connection con;

    public Autenticacao(Connection con) {
        this.con = con;
    }

    public boolean cadastrar(String nome, String email, String senha) {
        if (nome == null || nome.isBlank() || email == null || email.isBlank()
                || senha == null || senha.length() < 6) {
            System.out.println("Dados inválidos (senha precisa ter pelo menos 6 caracteres).");
            return false;
        }

        String sql = "INSERT INTO " + TABELA + " (" + COL_NOME + ", " + COL_EMAIL + ", " + COL_SENHA + ") VALUES (?, ?, ?)";

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, nome.trim());
            ps.setString(2, email.trim().toLowerCase());
            ps.setString(3, gerarHash(senha));
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {   // violação de UNIQUE
                System.out.println("Esse e-mail já está cadastrado.");
            } else {
                e.printStackTrace();
            }
            return false;
        }
    }

    public boolean login(String email, String senha) {
        if (email == null || senha == null) {
            return false;
        }

        String sql = "SELECT " + COL_SENHA + " FROM " + TABELA + " WHERE " + COL_EMAIL + " = ?";

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, email.trim().toLowerCase());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return verificarSenha(senha, rs.getString(COL_SENHA));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }


    private static String gerarHash(String senha) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash = pbkdf2(senha, salt);
            return Base64.getEncoder().encodeToString(salt) + ":"
                 + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Erro ao gerar hash da senha", e);
        }
    }

    private static boolean verificarSenha(String senha, String armazenado) {
        try {
            String[] partes = armazenado.split(":");
            byte[] salt = Base64.getDecoder().decode(partes[0]);
            byte[] esperado = Base64.getDecoder().decode(partes[1]);
            byte[] calculado = pbkdf2(senha, salt);
            return MessageDigest.isEqual(esperado, calculado);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String senha, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(senha.toCharArray(), salt, ITERACOES, TAMANHO_CHAVE);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                               .generateSecret(spec)
                               .getEncoded();
    }
}