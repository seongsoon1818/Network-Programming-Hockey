package hockey.client;

import hockey.client.ui.ClientFrame;

import javax.swing.SwingUtilities;

public final class ClientMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClientFrame().setVisible(true));
    }
}
