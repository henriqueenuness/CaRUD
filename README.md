
winget install -e --id EclipseAdoptium.Temurin.21.JDK


java -version      # versão do Java que executa os programas
javac -version     # versão do compilador (se der erro, falta o JDK)

cd $HOME\Documents\CaRUD


New-Item -ItemType Directory -Force lib

curl.exe -L -o lib/postgresql-42.7.4.jar https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar

Remove-Item -Recurse -Force out -ErrorAction SilentlyContinue

javac -cp "lib/*" -d out config/JdbcConnection.java funcao/Autenticacao.java funcao/Servidor.java


java -cp "out;lib/*" Servidor
