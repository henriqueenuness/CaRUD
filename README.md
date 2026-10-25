1. abrindo através do git (*GIT NÃO É GITHUB*, para seguir por esse método, é necessário ter instalado git(https://git-scm.com/)):
    1. dentro do vscode, aperte "CTRL+J"
    2. digite no terminal: git clone https://github.com/henriqueenuness/CaRUD
    3. depois: cd CaRUD (seguindo letras minusculas e maiusculas)
    4. seguido de: java -cp "out;lib/*" Servidor
    5. clique no http://localhost:8080 segurando a tecla "CTRL"

2. abrindo através do download:
    1. dentro do github, clique no botão verde "<>code", depois em "Download ZIP"
    2. clique com o botão direito no arquivo baixado e descompacte o arquivo
    3. abra o arquivo descompactado no seu vscode
    4. aperte "CTRL+J"
    5. seguido de: java -cp "out;lib/*" Servidor
    6. clique no http://localhost:8080 segurando a tecla "CTRL"


EM CASO DE ERRO DO COMANDO "java -cp "out;lib/*" Servidor":
    1. verifique a versão do java:
        no terminal:
        *java -version*
        depois:
        *javac -version*
    2. se java for 1.8 ou javac não existir, baixe e instale https://adoptium.net
    3. feche o terminal e o vscode, abra e tente executar o comando java -cp "out;lib/*" Servidor novamente

