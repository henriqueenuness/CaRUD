# 1. Instala o JDK 21 (kit do Java: o "java" executa e o "javac" compila)
#    -e = exige o nome exato do pacote | --id = qual pacote instalar
winget install -e --id EclipseAdoptium.Temurin.21.JDK

# >>> Feche o PowerShell e abra de novo (ele só enxerga o Java depois de reaberto)

# 2. Confere se o Java foi instalado (os dois precisam mostrar uma versão)
java -version      # versão do Java que executa os programas
javac -version     # versão do compilador (se der erro, falta o JDK)

# 3. Entra na pasta do projeto (o servidor procura public/html a partir da pasta atual)
cd $HOME\Documents\CaRUD

# 4. Cria a pasta lib, onde fica o driver do banco
#    -Force = não dá erro se a pasta já existir
New-Item -ItemType Directory -Force lib

# 5. Baixa o driver do PostgreSQL, que ensina o Java a falar com o Neon
#    -L = segue redirecionamentos | -o = onde salvar o arquivo
#    (use curl.exe com o .exe, senão o PowerShell chama outro comando)
curl.exe -L -o lib/postgresql-42.7.4.jar https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar

# 6. Apaga a pasta out (compilação antiga) para não sobrar versão velha do código
#    -Recurse = apaga o conteúdo | SilentlyContinue = sem erro se a pasta não existir
Remove-Item -Recurse -Force out -ErrorAction SilentlyContinue

# 7. Compila: transforma os .java (texto) em .class (o que o Java executa)
#    -cp "lib/*" = onde estão as bibliotecas (todos os .jar da pasta lib)
#    -d out      = guarda os arquivos compilados na pasta out
#    Sem mensagem no final = deu certo | "error:" = mostra arquivo e linha do problema
javac -cp "lib/*" -d out config/JdbcConnection.java funcao/Autenticacao.java funcao/Servidor.java

# 8. Liga o servidor (executa a classe Servidor, que tem o main)
#    -cp "out;lib/*" = procura o código compilado em out e o driver em lib
#    O ; separa os caminhos no Windows e as aspas são obrigatórias
#    Depois abra http://localhost:8080 no navegador | Para desligar: Ctrl+C
java -cp "out;lib/*" Servidor
