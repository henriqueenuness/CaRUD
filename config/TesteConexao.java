import java.sql.Connection;

public class TesteConexao {

public static void main(String[] args) {
config db = new config();
Connection con = db.getConnection();

if (con != null) {
System.out.println("Conectou!");
        } else {
System.out.println("Falhou. Veja o erro acima.");
        }

db.closeConnection();
    }
}