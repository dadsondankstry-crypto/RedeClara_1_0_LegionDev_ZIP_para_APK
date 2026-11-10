REDECLARA 1.0 - DIAGNOSTICO DE REDE / ANDROID
============================================

PROJETO PARA SEU COMPILADOR LEGIONDEV NO LENOVO LEGION GO

Este ZIP e um projeto Android Gradle COMPLETO. Nao e um APK pronto.
O sistema LegionDev, segundo o guia fornecido, seleciona o ZIP, extrai,
identifica settings.gradle na raiz e compila o modulo :app.

COMO COMPILAR NO LEGIONDEV:
1) No Legion Go, abra:
   C:\LegionDev\01_ANDROID_APK\03_COMPILAR_OUTRO_ZIP_APK_SEM_INTERNET.cmd
2) Na janela de escolha, selecione este arquivo ZIP.
3) Aguarde a compilacao.
4) Procure o APK em:
   C:\LegionDev\01_ANDROID_APK\APKs_GERADOS\
5) Instale o APK no POCO X7 Pro e abra o app.

REQUISITOS:
- Android 11 ou superior (POCO X7 Pro compativel)
- JDK 17
- Gradle 8.9
- Android SDK plataforma 35 + Build Tools 35.0.0
- Android Gradle Plugin 8.7.3 (precisa estar no cache Gradle local)
- Nao ha bibliotecas de terceiros, Compose nem AndroidX.

Se o cache Gradle nao tiver a versao do plugin 8.7.3, use antes,
com acesso a internet, o script do kit:
   C:\LegionDev\01_ANDROID_APK\05_PREPARAR_CACHE_DE_OUTRO_ZIP_COM_INTERNET.cmd
E depois utilize o script offline indicado acima.

O QUE FAZ:
- Exibe Wi-Fi, caminho padrao e indicador de VPN.
- Mostra dados de IP, gateway, DNS, DNS privado e MTU disponiveis.
- Mostra se o Android detectou portal ou validou acesso a internet.
- Executa testes de conectividade HTTPS e HTTP quando voce pede.
- Permite salvar uma referencia da rede e comparar depois de reconectar.
- Possui historico local, compartilhamento de relatorio e atalho para
  as configuracoes Android de Wi-Fi e VPN.

O QUE NAO FAZ:
- Nao troca ou falsifica MAC, IP, identidade de aparelho nem User-Agent
  para se passar por outro dispositivo ou servico.
- Nao limpa credenciais de outros apps, cookies do navegador ou registros
  remotos do portal.
- Nao burla limite de downloads, cota, login ou plano pago do provedor.
- Nao abre VPN nem cria tunnel de internet.

DADOS E PRIVACIDADE:
- Nao pede permissao de localizacao, contatos ou armazenamento.
- Historico e referencias ficam somente no armazenamento local do app.
- Teste de rede consulta connectivitycheck.gstatic.com (Google), apenas
  quando o usuario toca em Testar internet.
- Relatorio pode conter IP privado e DNS. Revise antes de compartilhar.
- 'Limpar historico' apaga apenas dados deste aplicativo.

ESTADO DE VALIDACAO:
A estrutura do ZIP e os arquivos XML foram validados automaticamente.
O APK NAO foi compilado nem testado em um POCO X7 Pro neste ambiente.
O resultado real depende do cache do compilador LegionDev.
