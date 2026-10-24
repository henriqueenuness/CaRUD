import java.sql.Connection;



        public static void main(String[] args) {
            JdbcConnection db = new JdbcConnection();
            Connection con = db.getConnection();
    
            if (con == null) {
                System.out.println("Sem conexão com o banco.");
                return;
            }
    
            Autenticacao auth = new Autenticacao(con);
    
            boolean cadastrou = auth.cadastrar("Heittor", "heittor@teste.com", "senha123");
            System.out.println("Cadastro: " + (cadastrou ? "OK" : "falhou"));
    
            System.out.println("Login certo:  " + auth.login("heittor@teste.com", "senha123"));
            System.out.println("Login errado: " + auth.login("heittor@teste.com", "outraSenha"));
    
            db.closeConnection();
        }
    
