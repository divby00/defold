package com.dynamo.cr.tileeditor.core;

import java.beans.PropertyChangeEvent;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.operations.IOperationHistory;
import org.eclipse.core.commands.operations.IUndoContext;
import org.eclipse.core.commands.operations.IUndoableOperation;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;

import com.dynamo.cr.properties.Entity;
import com.dynamo.cr.properties.IPropertyModel;
import com.dynamo.cr.properties.IPropertyObjectWorld;
import com.dynamo.cr.properties.Property;
import com.dynamo.cr.properties.PropertyIntrospector;
import com.dynamo.cr.properties.PropertyIntrospectorModel;
import com.dynamo.cr.tileeditor.Activator;
import com.dynamo.tile.proto.Tile;
import com.dynamo.tile.proto.Tile.TileGrid;
import com.dynamo.tile.proto.Tile.TileSet;
import com.google.protobuf.TextFormat;

@Entity(commandFactory = GridUndoableCommandFactory.class)
public class GridModel extends Model implements IPropertyObjectWorld, IAdaptable {

    private static PropertyIntrospector<Cell, GridModel> cellIntrospector = new PropertyIntrospector<Cell, GridModel>(Cell.class);
    public class Cell implements IAdaptable {

        @Override
        public Object getAdapter(@SuppressWarnings("rawtypes") Class adapter) {
            if (adapter == IPropertyModel.class) {
                return new PropertyIntrospectorModel<Cell, GridModel>(this, GridModel.this, cellIntrospector);
            }
            return null;
        }

        @Property
        private int tile;
        @Property
        private boolean hFlip;
        @Property
        private boolean vFlip;

        public int getTile() {
            return tile;
        }
        public void setTile(int tile) {
            this.tile = tile;
        }
        public boolean ishFlip() {
            return hFlip;
        }
        public void sethFlip(boolean hFlip) {
            this.hFlip = hFlip;
        }
        public boolean isvFlip() {
            return vFlip;
        }
        public void setvFlip(boolean vFlip) {
            this.vFlip = vFlip;
        }

    }

    private static PropertyIntrospector<Layer, GridModel> layerIntrospector = new PropertyIntrospector<Layer, GridModel>(Layer.class);
    public static class Layer implements IAdaptable {

        @Override
        public Object getAdapter(@SuppressWarnings("rawtypes") Class adapter) {
            if (adapter == IPropertyModel.class) {
                return new PropertyIntrospectorModel<Layer, GridModel>(this, gridModel, layerIntrospector);
            }
            return null;
        }

        @Property
        private String id;
        @Property
        private float z;
        @Property
        private boolean visible;

        GridModel gridModel;

        // upper 32-bits y, lower 32-bits x
        private final Map<Long, Cell> cells = new HashMap<Long, GridModel.Cell>();

        public Layer() {
        }

        public String getId() {
            return this.id;
        }

        public void setId(String id) {
            if ((this.id == null && id != null) || !this.id.equals(id)) {
                String oldId = this.id;
                this.id = id;
                if (gridModel != null)
                    gridModel.firePropertyChangeEvent(new PropertyChangeEvent(this, "id", oldId, id));
            }
        }

        public float getZ() {
            return this.z;
        }

        public void setZ(float z) {
            if (this.z != z) {
                float oldZ = this.z;
                this.z = z;
                if (gridModel != null)
                    gridModel.firePropertyChangeEvent(new PropertyChangeEvent(this, "z", oldZ, z));
            }
        }

        public boolean isVisible() {
            return this.visible;
        }

        public void setVisible(boolean visible) {
            if (this.visible != visible) {
                boolean oldVisible = this.visible;
                this.visible = visible;
                if (gridModel != null)
                    gridModel.firePropertyChangeEvent(new PropertyChangeEvent(this, "visible", oldVisible, visible));
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Layer) {
                Layer layer = (Layer)obj;
                return this.id.equals(layer.id)
                        && this.z == layer.z
                        && this.visible == layer.visible;
            } else {
                return super.equals(obj);
            }
        }

    }

    @Property(isResource = true)
    private String tileSet;
    @Property
    private float cellWidth;
    @Property
    private float cellHeight;

    private List<Layer> layers;
    private TileSetModel tileSetModel;

    private IContainer contentRoot;
    private final IOperationHistory undoHistory;
    private final IUndoContext undoContext;

    public GridModel(IContainer contentRoot, IOperationHistory history, IUndoContext undoContext) {
        this.contentRoot = contentRoot;
        this.undoHistory = history;
        this.undoContext = undoContext;

        this.layers = new ArrayList<Layer>();
        this.tileSetModel = new TileSetModel(contentRoot, null, null);
    }

    public String getTileSet() {
        return this.tileSet;
    }

    public void setTileSet(String tileSet) {
        if ((this.tileSet == null && tileSet != null) || !this.tileSet.equals(tileSet)) {
            String oldTileSet = this.tileSet;
            this.tileSet = tileSet;
            if (this.tileSet != null && !this.tileSet.equals("")) {
                loadTileSet();
                clearPropertyStatus("tileSet", Activator.STATUS_GRID_TS_NOT_SPECIFIED);
            } else {
                setPropertyStatus("tileSet", Activator.STATUS_GRID_TS_NOT_SPECIFIED);
            }
            firePropertyChangeEvent(new PropertyChangeEvent(this, "tileSet", oldTileSet, tileSet));
        }
    }

    private TileSet loadTileSetFile(String fileName) throws IOException, CoreException {
        IFile file = this.contentRoot.getFile(new Path(fileName));
        Reader input = new InputStreamReader(file.getContents());

        TileSet.Builder b = TileSet.newBuilder();

        try {
            TextFormat.merge(input, b);
            return b.build();
        } finally {
            input.close();
        }
    }

    private void loadTileSet() {
        try {
            clearPropertyStatus("tileSet", Activator.STATUS_GRID_TS_NOT_FOUND);
            clearPropertyStatus("tileSet", Activator.STATUS_GRID_INVALID_TILESET);
            TileSet tileSetMessage = loadTileSetFile(tileSet);
            this.tileSetModel.load(tileSetMessage);
            IStatus imageStatus = this.tileSetModel.getPropertyStatus("image");
            if (imageStatus != null && !imageStatus.isOK()) {
                setPropertyStatus("tileSet", Activator.STATUS_GRID_INVALID_TILESET, tileSet);
            }
        } catch (Exception e) {
            setPropertyStatus("tileSet", Activator.STATUS_GRID_TS_NOT_FOUND, tileSet);
        }
    }

    public float getCellWidth() {
        return this.cellWidth;
    }

    public void setCellWidth(float cellWidth) {
        boolean fire = this.cellWidth != cellWidth;

        float oldCellWidth = this.cellWidth;
        this.cellWidth = cellWidth;
        if (fire)
            firePropertyChangeEvent(new PropertyChangeEvent(this, "cellWidth", new Float(oldCellWidth), new Float(cellWidth)));
        if (cellWidth > 0) {
            clearPropertyStatus("cellWidth", Activator.STATUS_GRID_INVALID_CELL_WIDTH);
        } else {
            setPropertyStatus("cellWidth", Activator.STATUS_GRID_INVALID_CELL_WIDTH);
        }
    }

    public float getCellHeight() {
        return this.cellHeight;
    }

    public void setCellHeight(float cellHeight) {
        boolean fire = this.cellHeight != cellHeight;

        float oldCellHeight = this.cellHeight;
        this.cellHeight = cellHeight;
        if (fire)
            firePropertyChangeEvent(new PropertyChangeEvent(this, "cellHeight", new Float(oldCellHeight), new Float(cellHeight)));
        if (cellHeight > 0) {
            clearPropertyStatus("cellHeight", Activator.STATUS_GRID_INVALID_CELL_HEIGHT);
        } else {
            setPropertyStatus("cellHeight", Activator.STATUS_GRID_INVALID_CELL_HEIGHT);
        }
    }

    public List<Layer> getLayers() {
        return new ArrayList<Layer>(this.layers);
    }

    public void setLayers(List<Layer> layers) {
        boolean fire = this.layers.equals(layers);

        List<Layer> oldLayers = this.layers;
        this.layers = new ArrayList<Layer>(layers);

        Set<String> idSet = new HashSet<String>();
        boolean duplication = false;
        for (Layer layer : this.layers) {
            layer.gridModel = this;
            if (idSet.contains(layer.getId())) {
                duplication = true;
            } else {
                idSet.add(layer.getId());
            }
        }

        if (duplication) {
            setPropertyStatus("layers", Activator.STATUS_GRID_DUPLICATED_LAYER_IDS);
        } else {
            clearPropertyStatus("layers", Activator.STATUS_GRID_DUPLICATED_LAYER_IDS);
        }

        if (fire)
            firePropertyChangeEvent(new PropertyChangeEvent(this, "layers", oldLayers, layers));
    }

    public void load(TileGrid tileGrid) throws IOException {
        setTileSet(tileGrid.getTileSet());
        setCellWidth(tileGrid.getCellWidth());
        setCellHeight(tileGrid.getCellHeight());
        List<Layer> layers = new ArrayList<Layer>(tileGrid.getLayersCount());
        for (Tile.TileLayer layerDDF : tileGrid.getLayersList()) {
            Layer layer = new Layer();
            layer.gridModel = this;
            layer.setId(layerDDF.getId());
            layer.setZ(layerDDF.getZ());
            layer.setVisible(layerDDF.getIsVisible() != 0);
            layers.add(layer);
        }
        setLayers(layers);
    }

    private static PropertyIntrospector<GridModel, GridModel> introspector = new PropertyIntrospector<GridModel, GridModel>(GridModel.class);

    @Override
    public Object getAdapter(@SuppressWarnings("rawtypes") Class adapter) {
        if (adapter == IPropertyModel.class) {
            return new PropertyIntrospectorModel<GridModel, GridModel>(this, this, introspector);
        }
        return null;
    }

    public void executeOperation(IUndoableOperation operation) {
        operation.addContext(this.undoContext);
        IStatus status = null;
        try {
            status = this.undoHistory.execute(operation, null, null);
        } catch (final ExecutionException e) {
            Activator.logException(e);
        }

        if (status != Status.OK_STATUS) {
            Activator.logException(status.getException());
        }
    }

}
