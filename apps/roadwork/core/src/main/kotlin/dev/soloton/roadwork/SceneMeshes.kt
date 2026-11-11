package dev.soloton.roadwork

import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes.Usage

/** GL meshes in the vertex layout [MeshWriter] writes and the scene shader ([Shaders.SCENE_VERTEX]) reads. */
object SceneMeshes {
    /** An empty mesh with room for [maxVertices]; [static] for geometry that is uploaded once. */
    fun create(static: Boolean, maxVertices: Int) = Mesh(
        static, maxOf(maxVertices, 3), 0,
        VertexAttribute(Usage.Position, 3, "a_position"),
        VertexAttribute(Usage.Normal, 3, "a_normal"),
        VertexAttribute(Usage.ColorPacked, 4, GL20.GL_UNSIGNED_BYTE, true, "a_color"),
    )

    /** A static mesh holding what [w] currently contains. */
    fun static(w: MeshWriter): Mesh {
        val m = create(true, w.vertexCount)
        upload(m, w)
        return m
    }

    /** Replaces [mesh]'s vertices with what [w] currently contains. */
    fun upload(mesh: Mesh, w: MeshWriter) {
        mesh.setVertices(w.floats, 0, w.vertexCount * MeshWriter.STRIDE)
    }
}
