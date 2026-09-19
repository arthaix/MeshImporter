package ru.arthaix.meshimporter.compat;

import java.awt.image.BufferedImage;

import ru.arthaix.meshimporter.instance.MeshInstance;

/** A map mod that can draw placed models: the picture is the model seen from above, over its own footprint. */
public interface MeshMapOverlays {

    void show(MeshInstance instance, BufferedImage image);

    void hide(int dim, int id);

    void hideAll();
}
