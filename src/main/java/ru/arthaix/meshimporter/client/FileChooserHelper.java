package ru.arthaix.meshimporter.client;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.DefaultListModel;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

import net.minecraft.client.Minecraft;
import ru.arthaix.meshimporter.MeshImporter;

/** Opens a Swing file chooser (with a "recent files" side panel) on its own thread and hands the choice back to the client thread. */
public final class FileChooserHelper {

    private static volatile boolean open;

    private FileChooserHelper() {}

    public static void choose(String currentPath, List<String> recent, Consumer<String> onPick) {
        if (open) return;
        open = true;
        Thread t = new Thread(() -> {
            try {
                System.setProperty("java.awt.headless", "false");
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {}
                final String[] result = new String[1];
                SwingUtilities.invokeAndWait(() -> {
                    JFrame owner = new JFrame("MeshImporter");
                    owner.setUndecorated(true);
                    owner.setAlwaysOnTop(true);
                    owner.setSize(0, 0);
                    owner.setLocationRelativeTo(null);
                    owner.setVisible(true);
                    try {
                        JFileChooser chooser = new JFileChooser();
                        chooser.setDialogTitle("Choose a .obj model");
                        chooser.setFileFilter(new FileNameExtensionFilter("Wavefront OBJ (*.obj)", "obj"));
                        File cur = currentPath == null || currentPath.trim().isEmpty() ? null : new File(currentPath.trim());
                        if (cur != null && cur.isFile()) chooser.setSelectedFile(cur);
                        else if (cur != null && cur.getParentFile() != null && cur.getParentFile().isDirectory()) chooser.setCurrentDirectory(cur.getParentFile());
                        else if (!recent.isEmpty()) chooser.setCurrentDirectory(new File(recent.get(0)).getParentFile());

                        if (!recent.isEmpty()) {
                            DefaultListModel<String> model = new DefaultListModel<>();
                            for (String r : recent) model.addElement(r);
                            JList<String> list = new JList<>(model);
                            list.addListSelectionListener(ev -> {
                                String sel = list.getSelectedValue();
                                if (sel != null) chooser.setSelectedFile(new File(sel));
                            });
                            JPanel panel = new JPanel(new BorderLayout());
                            panel.setBorder(new TitledBorder("Recent"));
                            JScrollPane scroll = new JScrollPane(list);
                            scroll.setPreferredSize(new Dimension(320, 200));
                            panel.add(scroll, BorderLayout.CENTER);
                            chooser.setAccessory(panel);
                        }
                        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null)
                            result[0] = chooser.getSelectedFile().getAbsolutePath();
                    } finally {
                        owner.dispose();
                    }
                });
                if (result[0] != null) {
                    final String picked = result[0];
                    Minecraft.getMinecraft().addScheduledTask(() -> onPick.accept(picked));
                }
            } catch (Throwable e) {
                MeshImporter.logger.warn("file chooser failed: " + e);
            } finally {
                open = false;
            }
        }, "meshimporter-filechooser");
        t.setDaemon(true);
        t.start();
    }
}
