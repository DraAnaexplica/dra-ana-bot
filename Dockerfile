# Usar uma imagem base com Java 17
FROM openjdk:17-jdk-slim

# Definir o diretório de trabalho
WORKDIR /app

# Copiar os arquivos do projeto
COPY . .

# Tornar o mvnw executável
RUN chmod +x mvnw

# Construir o projeto com Maven
RUN ./mvnw package

# Definir o comando de inicialização
CMD ["java", "-jar", "target/dra-ana-bot-1.0-SNAPSHOT.jar"]