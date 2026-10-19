import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class JdbcConnection {

private Connection con = null;

private String hostName = null;
private String userName = null;
private String password = null;
private String url = null;
private String jdbcDriver = null;
private String dataBaseName = null;
private String dataBasePrefix = null;
private String dabaBasePort = null;

public JdbcConnection() {
super();

        hostName       = "ep-young-queen-b60h6lss-pooler.c-2.sa-east-1.aws.neon.tech";      // ex.: ep-xxxx.us-east-2.aws.neon.tech
        userName       = "neondb_owner";
        password       = "npg_NoVbe6jfCDa2";
        jdbcDriver     = "org.postgresql.Driver";
        dataBaseName   = "CaRUB";     // ex.: neondb
        dataBasePrefix = "jdbc:postgresql://";
        dabaBasePort   = "5432";

        url = dataBasePrefix + hostName + ":" + dabaBasePort + "/" + dataBaseName + "?sslmode=require";
    }

public Connection getConnection() {
try {
if (con == null) {
Class.forName(jdbcDriver);
                con = DriverManager.getConnection(url, userName, password);
            } else if (con.isClosed()) {
                con = null;
return getConnection();
            }
        } catch (ClassNotFoundException e) {
e.printStackTrace();
        } catch (SQLException e) {
e.printStackTrace();
        }
return con;
    }

public void closeConnection() {
if (con != null) {
try {
con.close();
            }catch (SQLException e){
e.printStackTrace();
            }
          }
        }
} 