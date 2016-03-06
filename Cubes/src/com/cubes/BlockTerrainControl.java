/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.cubes;

import java.io.IOException;
import java.util.ArrayList;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.AbstractControl;
import com.jme3.scene.control.Control;
import com.jme3.terrain.heightmap.ImageBasedHeightMap;
import com.jme3.texture.Texture;
import com.cubes.network.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;

/**
 *
 * @author Carl
 */
public class BlockTerrainControl extends AbstractControl implements BitSerializable{

    public static String keyify(Vector3Int key) {
        return "" + key.getX() + "." + key.getY() + '.' + key.getZ();
    }
    
    public static Vector3Int vectorify(String key) {
        String split[] = key.split("\\.");
        if (split.length != 3) {
            return null;
        }
        return new Vector3Int(Integer.parseInt(split[0]), Integer.parseInt(split[1]), Integer.parseInt(split[2]));
    }
    
    public BlockTerrainControl(CubesSettings settings, Vector3Int chunksCount){
        this.settings = settings;
        chunks = new HashMap<String, BlockChunkControl>();
    }
    private CubesSettings settings;
    private HashMap<String, BlockChunkControl> chunks;
    private ArrayList<BlockChunkListener> chunkListeners = new ArrayList<BlockChunkListener>();
    
    private void initializeChunk(Vector3Int location) {
        if (!chunks.containsKey(keyify(location))) {
            chunks.put(keyify(location), new BlockChunkControl(this, location.getX(), location.getY(), location.getZ()));
        }
    }

    @Override
    public void setSpatial(Spatial spatial){
        Spatial oldSpatial = this.spatial;
        super.setSpatial(spatial);
        for (String chunk :  chunks.keySet()) {
            if(spatial == null){
                oldSpatial.removeControl(chunks.get(chunk));
            }
            else{
                spatial.addControl(chunks.get(chunk));
            }
        }
    }

    @Override
    protected void controlUpdate(float lastTimePerFrame){
        updateSpatial();
    }

    @Override
    protected void controlRender(RenderManager renderManager, ViewPort viewPort){
        
    }

    @Override
    public Control cloneForSpatial(Spatial spatial){
        throw new UnsupportedOperationException("Not supported yet.");
    }
    
    public Block getBlock(int x, int y, int z){
        return getBlock(new Vector3Int(x, y, z));
    }
    
    public Block getBlock(Vector3Int location){
        BlockTerrain_LocalBlockState localBlockState = getLocalBlockState(location);
        if(localBlockState != null){
            return localBlockState.getBlock();
        }
        return null;
    }
    
    public void setBlockArea(Vector3Int location, Vector3Int size, Block block){
        Vector3Int tmpLocation = new Vector3Int();
        for(int x=0;x<size.getX();x++){
            for(int y=0;y<size.getY();y++){
                for(int z=0;z<size.getZ();z++){
                    tmpLocation.set(location.getX() + x, location.getY() + y, location.getZ() + z);
                    setBlock(tmpLocation, block);
                }
            }
        }
    }
    
    public void setBlock(int x, int y, int z, Block block){
        setBlock(new Vector3Int(x, y, z), block);
    }
    
    public void setBlock(Vector3Int location, Block block){
        this.initializeChunk(this.getChunkLocation(location));
        BlockTerrain_LocalBlockState localBlockState = getLocalBlockState(location);
        if(localBlockState != null){
            localBlockState.setBlock(block);
        }
    }
    
    public void removeBlockArea(Vector3Int location, Vector3Int size){
        Vector3Int tmpLocation = new Vector3Int();
        for(int x=0;x<size.getX();x++){
            for(int y=0;y<size.getY();y++){
                for(int z=0;z<size.getZ();z++){
                    tmpLocation.set(location.getX() + x, location.getY() + y, location.getZ() + z);
                    removeBlock(tmpLocation);
                }
            }
        }
    }
    
    public void removeBlock(int x, int y, int z){
        removeBlock(new Vector3Int(x, y, z));
    }
    
    public void removeBlock(Vector3Int location){
        BlockTerrain_LocalBlockState localBlockState = getLocalBlockState(location);
        if(localBlockState != null){
            localBlockState.removeBlock();
        }
    }
    
    private BlockTerrain_LocalBlockState getLocalBlockState(Vector3Int blockLocation){
        if(blockLocation.hasNegativeCoordinate()){
            return null;
        }
        BlockChunkControl chunk = getChunk(blockLocation);
        if(chunk != null){
            Vector3Int localBlockLocation = getLocalBlockLocation(blockLocation, chunk);
            return new BlockTerrain_LocalBlockState(chunk, localBlockLocation);
        }
        return null;
    }
    
    public void removeLightSource(HashMap<String, LightQueueElement> lightsToRemove) {
        if (lightsToRemove.size() == 0) {
            return;
        }
        for (LightQueueElement element : lightsToRemove.values()) {
            removeLightSource(getGlobalBlockLocation(element.getLocation(), element.getChunk()));
        }
     }

    public void addLightSource(HashMap<String, LightQueueElement> lightsToAdd) {
        if (lightsToAdd.size() == 0) {
            return;
        }
        //System.err.println("addLightSource " + lightsToAdd.size());
        if (!getSettings().getLightsEnabled()) {
            System.out.println("AddLightSource called with lights disabled");
            return;
        }
        class propigateElement {
            public Vector3Int location;
            public byte brightness;
            public propigateElement(Vector3Int location, byte brightness) {
                this.location = location;
                this.brightness = brightness;
            }
        }
        
        Queue<propigateElement> locationsToPropigateTo = new LinkedList<propigateElement>();
        Queue<propigateElement> next = new LinkedList<propigateElement>();
            
        for (LightQueueElement element : lightsToAdd.values()) {
            byte brightness = element.getLevel();
            Vector3Int globalLocation = getGlobalBlockLocation(element.getLocation(), element.getChunk());
            boolean placeLight = element.getPlaceLight();

            if (debugLogs) {
                System.out.println("addLightSource " + globalLocation + " level " + brightness + " place " + placeLight);
            }
            if (brightness <= 0) {
                continue;
            }

            BlockChunkControl chunk = element.getChunk();
            if (chunk == null) {
                continue;
            }
            Vector3Int localBlockLocation = element.getLocation();
            if (placeLight) {
                if (!chunk.addLightSource(localBlockLocation, brightness)) {
                    continue;
                }
            }
            // if the light source is brighter than the light currently at this spot
            if (chunk.propigateLight(localBlockLocation, brightness) || !placeLight) {
                if (brightness > 1) {
                    for(int face = 0; face < Block.Face.values().length; face++){
                        Vector3Int neighborLocation = BlockNavigator.getNeighborBlockLocalLocation(globalLocation, Block.Face.values()[face]);
                        locationsToPropigateTo.add(new propigateElement(neighborLocation, (byte)(brightness-1)));
                    }
                }
            }
        }
        
        while (locationsToPropigateTo.size() > 0) {
            propigateElement p = locationsToPropigateTo.remove();
            Vector3Int location = p.location;
            byte brightness = p.brightness;
            if (debugLogs) {
                System.out.println("addLightSource  locationsToPropigateTo " + location + " level " + brightness + " place ");
            }

            
            BlockChunkControl chunk;
            chunk = getChunk(location);
            Vector3Int localBlockLocation;
            if (chunk != null) {
                localBlockLocation = getLocalBlockLocation(location, chunk);
                if (chunk.propigateLight(localBlockLocation, brightness)) {
                    if (brightness > 1) {
                        for(int face = 0; face < Block.Face.values().length; face++){
                            Vector3Int neighborLocation = BlockNavigator.getNeighborBlockLocalLocation(location, Block.Face.values()[face]);
                            locationsToPropigateTo.add(new propigateElement(neighborLocation, (byte)(brightness-1)));
                        }
                    }
                }
            }
            if (locationsToPropigateTo.size() == 0) {
                locationsToPropigateTo = next;
                next = new LinkedList<propigateElement>();
            }
        }
    }

    public void addLightSource(Vector3Int globalLocation, byte brightness) {
        addLightSource(globalLocation, brightness, true);
        if (getLightLevelOfBlock(globalLocation) != getLightSourceOfBlock(globalLocation)) {
            addLightSource(globalLocation, brightness, true);
        }
    }

    
    public void addLightSource(Vector3Int globalLocation, byte brightness, boolean placeLight) {
        if (!getSettings().getLightsEnabled()) {
            System.out.println("AddLightSource called with lights disabled");
            return;
        }
        if (brightness <= 0) {
            return;
        }

        BlockChunkControl chunk = getChunk(globalLocation);
        if (chunk == null) {
            return;
        }
        Vector3Int localBlockLocation = getLocalBlockLocation(globalLocation, chunk);
        if (placeLight) {
            if (!chunk.addLightSource(localBlockLocation, brightness)) {
                return;
            }
        }

        // if the light source is brighter than the light currently at this spot
        if (chunk.propigateLight(localBlockLocation, brightness) || !placeLight) {
            brightness--;
            Queue<Vector3Int> locationsToPropigateTo = new LinkedList<Vector3Int>();
            Queue<Vector3Int> next = new LinkedList<Vector3Int>();
            for(int face = 0; face < Block.Face.values().length; face++){
                Vector3Int neighborLocation = BlockNavigator.getNeighborBlockLocalLocation(globalLocation, Block.Face.values()[face]);
                locationsToPropigateTo.add(neighborLocation);
            }
            while (locationsToPropigateTo.size() > 0 && brightness > 0) {
                Vector3Int location = locationsToPropigateTo.remove();
                chunk = getChunk(location);
                if (chunk != null) {
                    localBlockLocation = getLocalBlockLocation(location, chunk);
                    if (chunk.propigateLight(localBlockLocation, brightness)) {
                        for(int face = 0; face < Block.Face.values().length; face++){
                            Vector3Int neighborLocation = BlockNavigator.getNeighborBlockLocalLocation(location, Block.Face.values()[face]);
                            next.add(neighborLocation);
                        }
                    }
                }
                if (locationsToPropigateTo.size() == 0) {
                    locationsToPropigateTo = next;
                    next = new LinkedList<Vector3Int>();
                    brightness--;
                }
            }
        }
    }


    // TODO: Optimize this so it doesn't have to propigate as far
    // if it sees an incline in light level, it could stop propigatind darkness there
    // and propigate that light level instead.
    public static boolean debugLogs = false;
    public void removeLightSource(Vector3Int globalLocation) {
        if (!getSettings().getLightsEnabled()) {
            System.out.println("AddLightSource called with lights disabled");
            return;
        }
        BlockChunkControl chunk = getChunk(globalLocation);
        if (chunk == null) {
            return;
        }
        Vector3Int localBlockLocation = getLocalBlockLocation(globalLocation, chunk);
        byte oldLight = (byte)Math.max(chunk.getLightSourceAt(localBlockLocation), chunk.getLightAt(localBlockLocation));
        chunk.addLightSource(localBlockLocation, (byte)0);

        HashMap<String, LightQueueElement> lightsToReplace = new HashMap<String, LightQueueElement>();
        chunk.propigateDark(localBlockLocation, oldLight);//) {
        Queue<Vector3Int> locationsToPropigateTo = new LinkedList<Vector3Int>();
        Queue<Vector3Int> next = new LinkedList<Vector3Int>();
        for(int face = 0; face < Block.Face.values().length; face++){
            Vector3Int neighborLocation = BlockNavigator.getNeighborBlockLocalLocation(globalLocation, Block.Face.values()[face]);
            locationsToPropigateTo.add(neighborLocation);
        }
        oldLight--;
        boolean debugLogs = this.debugLogs;
        while (locationsToPropigateTo.size() > 0 && oldLight >= 0) {
            Vector3Int location = locationsToPropigateTo.remove();
            chunk = getChunk(location);
            if (chunk != null) {
                if (debugLogs) {
                    debugLogs = true;
                    System.out.println("removeLightSource while loop on " + location.toString());
                }
                localBlockLocation = getLocalBlockLocation(location, chunk);
                if (chunk.getLightSourceAt(localBlockLocation) > 0) {
                    if (debugLogs) {
                        System.out.println("source light > 0 " + location.toString());
                        System.out.println("add to lights to replace with " + location.toString() + " light:" + chunk.getLightSourceAt(localBlockLocation));
                    }                        
                    lightsToReplace.put(keyify(location), new LightQueueElement(localBlockLocation,chunk,chunk.getLightSourceAt(localBlockLocation),false));
                } else if (chunk.propigateDark(localBlockLocation, oldLight)) {
                    if (debugLogs) {
                        System.out.println("adding faces > 0 " + location.toString());
                    }
                    for(int face = 0; face < Block.Face.values().length; face++){
                        Vector3Int neighborLocation = BlockNavigator.getNeighborBlockLocalLocation(location, Block.Face.values()[face]);
                        next.add(neighborLocation);
                    }
                } else {
                    if (debugLogs) {
                        System.out.println("else " + location.toString());
                    }
                    byte light = chunk.getLightAt(localBlockLocation);
                    if(light > 0) {
                        if (debugLogs) {
                            System.out.println("light at > 0 " + location.toString());
                            System.out.println("add to lights to replace with " + location.toString() + " light:" + light);
                        }
                        lightsToReplace.put(keyify(location), new LightQueueElement(localBlockLocation,chunk,light,false));
                    }
                }
                if (locationsToPropigateTo.size() == 0) {
                    if (debugLogs) {
                        System.out.println("next cycle");
                    }
                    locationsToPropigateTo = next;
                    next = new LinkedList<Vector3Int>();
                    oldLight--;
                }
            }
        }
        addLightSource(lightsToReplace);
    }
    
    public BlockChunkControl getChunk(Vector3Int blockLocation){
        if(blockLocation.hasNegativeCoordinate()){
            return null;
        }
        Vector3Int chunkLocation = getChunkLocation(blockLocation);
        if(isValidChunkLocation(chunkLocation)){
            return chunks.get(keyify(chunkLocation));
        }
        return null;
    }
    
    public boolean isValidChunkLocation(Vector3Int location){
        return chunks.containsKey(keyify(location));
    }
    
    public boolean getIsGlobalLocationAboveSurface(Vector3Int blockLocation) {
        if(blockLocation.hasNegativeCoordinate()){
            return true;
        }
        Vector3Int chunkLoc = getChunkLocation(blockLocation);
        String key = keyify(chunkLoc);
        BlockChunkControl chunk = chunks.get(key);
        if (chunk == null) {
            return true; // missing chunks don't block light
        }
        return chunk.isBlockAboveSurface(getLocalBlockLocation(blockLocation, chunk));
    }
    
    /** Get chunk location from block location */
    public Vector3Int getChunkLocation(Vector3Int blockLocation){
        Vector3Int chunkLocation = new Vector3Int();
        int chunkX = (blockLocation.getX() / settings.getChunkSizeX());
        int chunkY = (blockLocation.getY() / settings.getChunkSizeY());
        int chunkZ = (blockLocation.getZ() / settings.getChunkSizeZ());
        chunkLocation.set(chunkX, chunkY, chunkZ);
        return chunkLocation;
    }

    /*
    public Vector3Int getLocalBlockLocation(Vector3Int blockLocation){
        Vector3Int localLocation = new Vector3Int();
        int localX = (blockLocation.getX() % settings.getChunkSizeX());
        int localY = (blockLocation.getY() % settings.getChunkSizeY());
        int localZ = (blockLocation.getZ() % settings.getChunkSizeZ());
        localLocation.set(localX, localY, localZ);
        return localLocation;
    }
    */
    public static Vector3Int getGlobalBlockLocation(Vector3Int localLocation, BlockChunkControl chunk) {
        Vector3Int globalLocation = new Vector3Int();
        int localX = (localLocation.getX() + chunk.getBlockLocation().getX());
        int localY = (localLocation.getY() + chunk.getBlockLocation().getY());
        int localZ = (localLocation.getZ() + chunk.getBlockLocation().getZ());
        globalLocation.set(localX, localY, localZ);
        return globalLocation;
    }
    public static Vector3Int getLocalBlockLocation(Vector3Int globalBlockLocation, BlockChunkControl chunk){
        Vector3Int localLocation = new Vector3Int();
        int localX = (globalBlockLocation.getX() - chunk.getBlockLocation().getX());
        int localY = (globalBlockLocation.getY() - chunk.getBlockLocation().getY());
        int localZ = (globalBlockLocation.getZ() - chunk.getBlockLocation().getZ());
        localLocation.set(localX, localY, localZ);
        return localLocation;
    }
    
    public boolean updateSpatial(){
        boolean wasUpdatedNeeded = false;
        for (String chunkLocation :  chunks.keySet()) {
            BlockChunkControl chunk = chunks.get(chunkLocation);
            if(chunk.updateSpatial()){
                wasUpdatedNeeded = true;
                for(int i=0;i<chunkListeners.size();i++){
                    BlockChunkListener blockTerrainListener = chunkListeners.get(i);
                    blockTerrainListener.onSpatialUpdated(chunk);
                }
            }
           
        }
        return wasUpdatedNeeded;
    }
    
    public void updateBlockMaterial(){
        for (String chunkLocation :  chunks.keySet()) {
            BlockChunkControl chunk = chunks.get(chunkLocation);
                 chunk.updateBlockMaterial();
        }
    }
    
    public void addChunkListener(BlockChunkListener blockChunkListener){
        chunkListeners.add(blockChunkListener);
    }
    
    public void removeChunkListener(BlockChunkListener blockChunkListener){
        chunkListeners.remove(blockChunkListener);
    }
    
    public CubesSettings getSettings(){
        return settings;
    }

    public HashMap<String, BlockChunkControl> getChunks(){
        return chunks;
    }
    
    //Tools
    
    public void setBlocksFromHeightmap(Vector3Int location, String heightmapPath, int maximumHeight, Block block){
        try{
            Texture heightmapTexture = settings.getAssetManager().loadTexture(heightmapPath);
            ImageBasedHeightMap heightmap = new ImageBasedHeightMap(heightmapTexture.getImage(), 1f);
            heightmap.load();
            heightmap.setHeightScale(maximumHeight / 255f);
            setBlocksFromHeightmap(location, getHeightmapBlockData(heightmap.getScaledHeightMap(), heightmap.getSize()), block);
        }catch(Exception ex){
            System.out.println("Error while loading heightmap '" + heightmapPath + "'.");
        }
    }

    private static int[][] getHeightmapBlockData(float[] heightmapData, int length){
        int[][] data = new int[heightmapData.length / length][length];
        int x = 0;
        int z = 0;
        for(int i=0;i<heightmapData.length;i++){
            data[x][z] = (int) Math.round(heightmapData[i]);
            x++;
            if((x != 0) && ((x % length) == 0)){
                x = 0;
                z++;
            }
        }
        return data;
    }

    public void setBlocksFromHeightmap(Vector3Int location, int[][] heightmap, Block block){
        Vector3Int tmpLocation = new Vector3Int();
        Vector3Int tmpSize = new Vector3Int();
        for(int x=0;x<heightmap.length;x++){
            for(int z=0;z<heightmap[0].length;z++){
                tmpLocation.set(location.getX() + x, location.getY(), location.getZ() + z);
                tmpSize.set(1, heightmap[x][z], 1);
                setBlockArea(tmpLocation, tmpSize, block);
            }
        }
    }
    
    public void setBlocksFromNoise(Vector3Int location, Vector3Int size, float roughness, Block block){
        Noise noise = new Noise(null, roughness, size.getX(), size.getZ());
        noise.initialise();
        float gridMinimum = noise.getMinimum();
        float gridLargestDifference = (noise.getMaximum() - gridMinimum);
        float[][] grid = noise.getGrid();
        for(int x=0;x<grid.length;x++){
            float[] row = grid[x];
            for(int z=0;z<row.length;z++){
                /*---Calculation of block height has been summarized to minimize the java heap---
                float gridGroundHeight = (row[z] - gridMinimum);
                float blockHeightInPercents = ((gridGroundHeight * 100) / gridLargestDifference);
                int blockHeight = ((int) ((blockHeightInPercents / 100) * size.getY())) + 1;
                ---*/
                int blockHeight = (((int) (((((row[z] - gridMinimum) * 100) / gridLargestDifference) / 100) * size.getY())) + 1);
                Vector3Int tmpLocation = new Vector3Int();
                this.initializeChunk(this.getChunkLocation(tmpLocation));
                for(int y=0;y<blockHeight;y++){
                    tmpLocation.set(location.getX() + x, location.getY() + y, location.getZ() + z);
                    setBlock(tmpLocation, block);
                }
            }
        }
    }
    
    public void setBlocksForMaximumFaces(Vector3Int location, Vector3Int size, Block block){
        Vector3Int tmpLocation = new Vector3Int();
        for(int x=0;x<size.getX();x++){
            for(int y=0;y<size.getY();y++){
                for(int z=0;z<size.getZ();z++){
                    if(((x ^ y ^ z) & 1) == 1){
                        tmpLocation.set(location.getX() + x, location.getY() + y, location.getZ() + z);
                        setBlock(tmpLocation, block);
                    }
                }
            }
        }
    }

    @Override
    public BlockTerrainControl clone(){
        BlockTerrainControl blockTerrain = new BlockTerrainControl(settings, new Vector3Int());
        blockTerrain.setBlocksFromTerrain(this);
        return blockTerrain;
    }
    
    public void setBlocksFromTerrain(BlockTerrainControl blockTerrain){
        CubesSerializer.readFromBytes(this, CubesSerializer.writeToBytes(blockTerrain));
    }

    @Override
    public void write(BitOutputStream outputStream){
        outputStream.writeInteger(chunks.keySet().size());
        for (String chunkLocation :  chunks.keySet()) {
            BlockChunkControl chunk = chunks.get(chunkLocation);
            Vector3Int vChunkLocation = vectorify(chunkLocation);
            outputStream.writeInteger(vChunkLocation.getX());
            outputStream.writeInteger(vChunkLocation.getY());
            outputStream.writeInteger(vChunkLocation.getZ());
            chunk.write(outputStream);
        }
    }

    public ArrayList<byte[]> writeChunkPartials(Vector3Int chunkLoc) {
        ArrayList<byte[]> returnValue = new ArrayList<byte[]>();
        String chunkLocation = keyify(chunkLoc);
        BlockChunkControl chunk = chunks.get(chunkLocation);
        Vector3Int vChunkLocation = vectorify(chunkLocation);
        for(int i = 0; i < settings.getChunkSizeY(); i++) {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            BitOutputStream bitOutputStream = new BitOutputStream(byteArrayOutputStream);            
            bitOutputStream.writeInteger(vChunkLocation.getX());
            bitOutputStream.writeInteger(vChunkLocation.getY());
            bitOutputStream.writeInteger(vChunkLocation.getZ()); // is this always 0?
            bitOutputStream.writeInteger(i); // Virticle slice of chunk
            chunk.write(i, bitOutputStream);
            bitOutputStream.close();
            byte[] chunkBytes = byteArrayOutputStream.toByteArray();
            returnValue.add(chunkBytes);
        }
        return returnValue;
    }

    @Override
    public void read(BitInputStream inputStream) throws IOException{
        int chunkCount = inputStream.readInteger();
        while (chunkCount > 0) {
            int chunkX = inputStream.readInteger();
            int chunkY = inputStream.readInteger();
            int chunkZ = inputStream.readInteger();
            Vector3Int chunkLocation;
            chunkLocation = new Vector3Int(chunkX, chunkY, chunkZ);
            initializeChunk(chunkLocation);
            BlockChunkControl chunk = chunks.get(keyify(chunkLocation));
            chunk.read(inputStream);
            --chunkCount;
        }
    }

    public void readChunkPartial(BitInputStream inputStream) throws IOException{
        int chunkX = inputStream.readInteger();
        int chunkY = inputStream.readInteger();
        int chunkZ = inputStream.readInteger();
        int chunkSlice = inputStream.readInteger();
        Vector3Int chunkLocation;
        chunkLocation = new Vector3Int(chunkX, chunkY, chunkZ);
        initializeChunk(chunkLocation);
        BlockChunkControl chunk = chunks.get(keyify(chunkLocation));
        chunk.read(chunkSlice, inputStream);
    }
    
    public void readChunkPartial(byte data[]) {
         BitInputStream bitInputStream = new BitInputStream(new ByteArrayInputStream(data));
         try {
             this.readChunkPartial(bitInputStream);
         } catch(IOException ex){
             ex.printStackTrace();
         }
    }

    byte getLightLevelOfBlock(Vector3Int globalLocation) {
        BlockChunkControl chunk = getChunk(globalLocation);
        if (chunk == null) {
            return 0;
        }
        Vector3Int localBlockLocation = getLocalBlockLocation(globalLocation, chunk);
        return chunk.getLightAt(localBlockLocation);
    }
    
    byte getLightSourceOfBlock(Vector3Int globalLocation) {
        BlockChunkControl chunk = getChunk(globalLocation);
        if (chunk == null) {
            return 0;
        }
        Vector3Int localBlockLocation = getLocalBlockLocation(globalLocation, chunk);
        return chunk.getLightSourceAt(localBlockLocation);
    }


}
