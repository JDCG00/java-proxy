import java.io.*;
import java.net.*;
import java.util.HashSet;
import java.util.Set;

public class SecureProxyServer {
    public static void main(String[] args) {
        int localPort = 8080; // Puerto donde escuchará el proxy

        try (ServerSocket serverSocket = new ServerSocket(localPort)) {
            System.out.println("Proxy server listening on port " + localPort);

            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("Accepted connection from client");

                    // Crear un nuevo hilo para manejar la conexión del cliente
                    new Thread(new ProxyThread(clientSocket)).start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class ProxyThread implements Runnable {
    private Socket clientSocket;

    // Lista de URLs bloqueadas
    private static final Set<String> BLOCKED_URLS = new HashSet<>();

    static {
        // Agregar URLs bloqueadas a la lista
        BLOCKED_URLS.add("www.twitch.tv");
        BLOCKED_URLS.add("www.youtube.com");
    }

    public ProxyThread(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        try (
                InputStream clientInput = clientSocket.getInputStream();
                OutputStream clientOutput = clientSocket.getOutputStream();
                BufferedReader clientReader = new BufferedReader(new InputStreamReader(clientInput));
                BufferedWriter clientWriter = new BufferedWriter(new OutputStreamWriter(clientOutput));) {
            String requestLine = clientReader.readLine();
            if (requestLine == null || requestLine.isEmpty())
                return;

            System.out.println("Request: " + requestLine);

            String[] parts = requestLine.split(" ");
            if (parts.length < 2)
                return; // Verifica que la solicitud tenga al menos 2 partes

            String method = parts[0];
            String url = parts[1];

            URI uri;
            try {
                uri = new URI("http://" + url); // Prepend "http://" if URL does not include scheme
            } catch (URISyntaxException e) {
                e.printStackTrace();
                return;
            }

            String host = uri.getHost();
            System.out.println("Host: " + host);
            if (host != null && BLOCKED_URLS.contains(host)) {
                handleBlockedURL(clientWriter, host);
            } else {
                if (method.equals("CONNECT")) {
                    handleConnect(url, clientOutput, clientInput);
                } else {
                    handleHttpRequest(requestLine, clientReader, clientWriter);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void handleBlockedURL(BufferedWriter clientWriter, String host) throws IOException {
        System.out.println("Blocked URL: " + host);
        // Responder con un mensaje de bloqueo
        clientWriter.write("HTTP/1.1 403 Forbidden\r\n");
        clientWriter.write("Content-Type: text/html\r\n");
        clientWriter.write("\r\n");
        clientWriter.write("<html><body><h1>403 Forbidden</h1><p>This URL is blocked.</p></body></html>");
        clientWriter.flush();
    }

    private void handleConnect(String url, OutputStream clientOutput, InputStream clientInput) {
        String[] urlParts = url.split(":");
        String remoteHost = urlParts[0];
        int remotePort = urlParts.length > 1 ? Integer.parseInt(urlParts[1]) : 443; // HTTPS generalmente usa puerto 443

        try (
                Socket remoteSocket = new Socket(remoteHost, remotePort);
                InputStream remoteInput = remoteSocket.getInputStream();
                OutputStream remoteOutput = remoteSocket.getOutputStream();) {
            clientOutput.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
            clientOutput.flush();

            Thread clientToRemote = new Thread(() -> forwardData(clientInput, remoteOutput));
            Thread remoteToClient = new Thread(() -> forwardData(remoteInput, clientOutput));

            clientToRemote.start();
            remoteToClient.start();

            clientToRemote.join();
            remoteToClient.join();

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void handleHttpRequest(String requestLine, BufferedReader clientReader, BufferedWriter clientWriter) {
        try {
            URI remoteUri = new URI(requestLine.split(" ")[1]);
            String remoteHost = remoteUri.getHost();
            int remotePort = remoteUri.getPort() == -1 ? 80 : remoteUri.getPort();

            try (Socket remoteSocket = new Socket(remoteHost, remotePort);
                    BufferedWriter remoteWriter = new BufferedWriter(
                            new OutputStreamWriter(remoteSocket.getOutputStream()));
                    BufferedReader remoteReader = new BufferedReader(
                            new InputStreamReader(remoteSocket.getInputStream()))) {

                remoteWriter.write(requestLine + "\r\n");

                String header;
                while (!(header = clientReader.readLine()).isEmpty()) {
                    remoteWriter.write(header + "\r\n");
                }
                remoteWriter.write("\r\n");
                remoteWriter.flush();

                String responseLine;
                while ((responseLine = remoteReader.readLine()) != null) {
                    clientWriter.write(responseLine + "\r\n");
                }
                clientWriter.write("\r\n");
                clientWriter.flush();
            }
        } catch (IOException | URISyntaxException e) {
            e.printStackTrace();
        }
    }

    private void forwardData(InputStream input, OutputStream output) {
        byte[] buffer = new byte[4096];
        int bytesRead;
        try {
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
                output.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}