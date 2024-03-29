package com.mweis.game.world;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ai.pfa.Connection;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntMap;
import com.badlogic.gdx.utils.ObjectSet;
import com.badlogic.gdx.utils.IntMap.Keys;
import com.badlogic.gdx.utils.ObjectMap;
import com.mweis.game.world.Room.RoomType;
import com.mweis.game.world.graph.DGraph;
import com.mweis.game.world.graph.Edge;

/*
 * Implementation of a Square Dungeon
 */
public class Dungeon {
		
	public final int WIDTH, HEIGHT, MIN_SIDE_LENGTH, MAX_SIDE_LENGTH, HALL_WIDTH,
		CORRIDOR_COUNT, ROOM_COUNT, HALL_COUNT,
		// adjust this if units walking through walls is an issue:
		MAX_BOX2D_STATIC_BODY_SIZE = 50; // no effect if body size less than 2*blockSize (?changed?)
	public final float MIN_RATIO, MAX_RATIO;
	
	private Room startRoom, endRoom;
	private Array<Room> noncriticalRooms, criticalRooms, allRooms, halls, dungeon;
	private DGraph<Room> criticalRoomGraph;
	private DGraph<Room> dungeonGraph;
	private DGraph<Vector2> hallwayGraph;
	
	public final int UNITS_PER_PARTITION = 30, // width and height of each partition square
			ESTIMATED_MAX_ROOMS_PER_PARTITION = 12, // used for init cap of ObjectSet, THIS IS ALWAYS ROUNDED UP TO NEXT POWER OF TWO
			PARTITION_WIDTH; // with in CHUNKS
	private final IntMap<Array<Room>> spatialPartition; // where Integer is x+y*unitsPerPartition coord
	
	// make dungeon constructor take Blueprint!
//	Dungeon(Room start, Room end, Array<Room> rooms, Array<Room> corridors, Array<Room> halls, DGraph<Room> criticalRoomGraph,
//			int minSideLength, int maxSideLength, int hallWidth, float minRatio, float maxRatio) {
	public Dungeon(DungeonBlueprint blueprint) {
		
		this.startRoom = blueprint.getStart();
		this.endRoom = blueprint.getEnd();
		this.noncriticalRooms = blueprint.getRooms();
		this.criticalRooms = blueprint.getCorridors();
		this.allRooms = new Array<Room>(blueprint.getRooms());
		this.allRooms.addAll(blueprint.getCorridors());
		this.halls =  blueprint.getHalls();
		this.criticalRoomGraph = blueprint.getCriticalRoomGraph();
		this.MIN_SIDE_LENGTH = blueprint.getMinSideLength();
		this.MAX_SIDE_LENGTH = blueprint.getMaxSideLength();
		this.HALL_WIDTH = blueprint.getHallWidth();
		this.CORRIDOR_COUNT = blueprint.getCorridors().size;
		this.ROOM_COUNT = blueprint.getRooms().size;
		this.HALL_COUNT = blueprint.getHalls().size;
		this.MIN_RATIO = blueprint.getMinRatio();
		this.MAX_RATIO = blueprint.getMaxRatio();
		
		this.dungeon = new Array<Room>();
		
		/*
		 * Add all rooms to dungeon and mark their type
		 */
		for (Room room : blueprint.getRooms()) {
			this.dungeon.add(room);
			room.setType(RoomType.NONCRITICAL);
		}
		for (Room corridor : blueprint.getCorridors()) {
			this.dungeon.add(corridor);
			corridor.setType(RoomType.CRITICAL);
		}
		for (Room hall : blueprint.getHalls()) {
			this.dungeon.add(hall);
			hall.setType(RoomType.HALLWAY);
		}
		
		/*
		 * Normalize dungeon and create it's graph
		 */
		this.putDungeonInWorldSpace();
		
		this.WIDTH = this.calculateWidth();
		this.HEIGHT = this.calculateHeight();
		
		this.PARTITION_WIDTH = (int) Math.ceil((double)this.WIDTH / this.UNITS_PER_PARTITION);
		this.spatialPartition = this.createSpatialParition();
		
		this.dungeonGraph = this.createDungeonGraph();	
		this.hallwayGraph = this.createHallwayGraph();
	}
	
	public Array<Room> getDungeon() {
		return dungeon;
	}
	
	public Array<Room> getOptionalRooms() {
		return noncriticalRooms;
	}
	
	public Array<Room> getHalls() {
		return halls;
	}
	
	public Array<Room> getRooms() { // rooms only, not halls
		return allRooms;
	}
	
	public Array<Room> getCriticalRooms() {
		return criticalRooms;
	}
	
	public DGraph<Room> getCriticalRoomGraph() {
		return criticalRoomGraph;
	}
	
	public DGraph<Room> getDungeonGraph() {
		return dungeonGraph;
	}
	
	public Room getStartRoom() {
		return startRoom;
	}
	
	public Room getEndRoom() {
		return endRoom;
	}
	
	/*
	 * Because dungeons are created in the realm of -RADIUS to RADIUS, and are never guarenteed to hit the borders,
	 * this function will normalize the rightmost and bottommost rooms to (0,y) and (x, 0) respectively
	 * this should only be called once in the constructor
	 */
	private void putDungeonInWorldSpace() {
		int leftmostWall = Integer.MAX_VALUE;
		int bottomWall = Integer.MAX_VALUE;
		for (Room room : this.getDungeon()) {
			if (room.getLeft() < leftmostWall) {
				leftmostWall = room.getLeft();
			}
			if (room.getBottom() < bottomWall) {
				bottomWall = room.getBottom();
			}
		}
		
		// usually this will shift up and right, but if the leftmost happens to be positive (which is extremely unlikely) this still orients it to 0,0
		int dx = -leftmostWall;
		int dy = -bottomWall;
		
		for (Room room : this.getDungeon()) {
			room.shift(dx, dy);
		}
	}
	
	/*
	 * precondition: dungeonGraph is completed
	 * creates a graph of Vector2's which can be used for pathfinding algorithms.
	 */
	Array<Vector2> points = new Array<Vector2>();
	private DGraph<Vector2> createHallwayGraph() {
		
		DGraph<Vector2> graph = new DGraph<Vector2>();
		// because a hallway will always connect two rooms, we use this as a reference for graph building
//		for (Room hall : this.getHalls()) {
//			for (Room room : this.getDungeon()) {
//				if (room.getType() == RoomType.CRITICAL) { // this also implicitly checks that hall != room
//					if (hall.touches(room)) {
//						float dist = hall.getCenter().dst(room.getCenter());						
//						if (!graph.hasKey(hall.getCenter())) {
//							graph.addKey(hall.getCenter());
//						}
//						graph.addConnection(hall.getCenter(), new Edge<Vector2>(hall.getCenter(), room.getCenter(), dist));
//						
//						if (!graph.hasKey(room.getCenter())) {
//							graph.addKey(room.getCenter());
//						}
//						graph.addConnection(room.getCenter(), new Edge<Vector2>(room.getCenter(), hall.getCenter(), dist));
//					}
//				}
//			}
//		}
	
		for (Room criticalRoom : this.getDungeonGraph().getKeys()) {
			if (criticalRoom.getType() == RoomType.CRITICAL) {
				for (Connection<Room> roomToHall : this.getDungeonGraph().getConnections(criticalRoom)) {
					Room hall = roomToHall.getToNode();
					if (hall.getType() == RoomType.HALLWAY) {
						// if a criticalRoom -> hall connection exists
						float dist = hall.getCenter().dst(criticalRoom.getCenter());						
						if (!graph.hasKey(hall.getCenter())) {
							graph.addKey(hall.getCenter());
						}
						graph.addConnection(hall.getCenter(), new Edge<Vector2>(hall.getCenter(), criticalRoom.getCenter(), dist));
						
						if (!graph.hasKey(criticalRoom.getCenter())) {
							graph.addKey(criticalRoom.getCenter());
						}
						graph.addConnection(criticalRoom.getCenter(), new Edge<Vector2>(criticalRoom.getCenter(), hall.getCenter(), dist));
					}
				}
			}
		}
		
		/*
		 * Key insight is that associated hallways are always added together!
		 * so we can iterate in pairs
		 * 
		 * .ILL NOT WORKING! HALLS PATHS CAN OVERLAP COLLIDERS
		 */
		for (int i=0, j = 1; i < getHalls().size; i += 2, j += 2) {
			
//			for (int j=i+1; j < getHalls().size; j++) {
				Room h1 = getHalls().get(i);
				Room h2 = getHalls().get(j);
				
//				if (h1.touches(h2)) {
					
					float minTop = Math.min(h1.getTop(), h2.getTop());
					float maxBot = Math.min(h1.getBottom(), h2.getBottom());
					float maxLeft = Math.min(h1.getLeft(), h2.getLeft());
					float minRight = Math.min(h1.getRight(), h2.getRight());
					
					float verticalDiff = minTop - maxBot;
					float horizontalDiff = minRight - maxLeft;
					
					Vector2 overlapMidpoint = new Vector2(maxLeft + horizontalDiff/2.0f, maxBot + verticalDiff/2.0f);
					points.add(overlapMidpoint);
					graph.addKey(overlapMidpoint);
					
					if (!graph.hasKey(h1.getCenter())) {
						graph.addKey(h1.getCenter());
					}
					graph.addConnection(h1.getCenter(), new Edge<Vector2>(h1.getCenter(), overlapMidpoint,
							h1.getCenter().dst(overlapMidpoint)));
//					System.out.println(overlapMidpoint + ", " + h1.getCenter());
					
					graph.addConnection(overlapMidpoint, new Edge<Vector2>(overlapMidpoint, h1.getCenter(),
							h1.getCenter().dst(overlapMidpoint)));
					
					if (!graph.hasKey(h2.getCenter())) {
						graph.addKey(h2.getCenter());
					}
					graph.addConnection(h2.getCenter(), new Edge<Vector2>(h2.getCenter(), overlapMidpoint,
							h2.getCenter().dst(overlapMidpoint)));
					graph.addConnection(overlapMidpoint, new Edge<Vector2>(overlapMidpoint, h2.getCenter(),
							h2.getCenter().dst(overlapMidpoint)));
					
					
//					Vector2 h1Enter = null;
//					Vector2 h2Enter = null;
//					
//					boolean h1Horizontal = h1.getWidth() > h1.getHeight();
//					boolean h2Horizontal = h2.getWidth() > h2.getHeight();
//					
////					if (h1Horizontal == h2Horizontal) { // skip over halls not meant to intersect
////						continue;
////					}
//					
//					if (h1Horizontal) {  // if horizontal hallway
//						// check if the overlapMidpoint is closer to left or right to get orientation
//						if (Math.abs(h1.getLeft() - overlapMidpoint.x) < Math.abs(h1.getRight() - overlapMidpoint.x)) {
//							// opening is on right
//							h1Enter = new Vector2(h1.getRight(), h1.getCenterY()); //overlapMidpoint.y);
//						} else {
//							// opening is on left
//							h1Enter = new Vector2(h1.getLeft(), h1.getCenterY()); //overlapMidpoint.y);
//						}
//					} else { // if vertical hallway
//						// check if the overlapMidpoint is closer to top or bottom to get orientation
//						if (Math.abs(h1.getBottom() - overlapMidpoint.y) < Math.abs(h1.getTop() - overlapMidpoint.y)) {
//							// opening is on top
////							h1Enter = new Vector2(overlapMidpoint.x, h1.getTop());
//							h1Enter = new Vector2(h1.getCenterX(), h1.getTop());
//						} else {
//							// opening is on bottom
//							h1Enter = new Vector2(h1.getCenterX(), h1.getBottom());
//						}
//					}
//					
//					if (h2Horizontal) {  // if horizontal hallway
//						// check if the overlapMidpoint is closer to left or right to get orientation
//						if (Math.abs(h2.getLeft() - overlapMidpoint.x) < Math.abs(h2.getRight() - overlapMidpoint.x)) {
//							// opening is on right
//							h2Enter = new Vector2(h2.getRight(), h2.getCenterY()); //overlapMidpoint.y);
//						} else {
//							// opening is on left
//							h2Enter = new Vector2(h2.getLeft(), h2.getCenterY()); //overlapMidpoint.y);
//						}
//					} else { // if vertical hallway
//						// check if the overlapMidpoint is closer to top or bottom to get orientation
//						if (Math.abs(h2.getBottom() - overlapMidpoint.y) < Math.abs(h2.getTop() - overlapMidpoint.y)) {
//							// opening is on top
//							h2Enter = new Vector2(h2.getCenterX(), h2.getTop());
//						} else {
//							// opening is on bottom
//							h2Enter = new Vector2(h2.getCenterX(), h2.getBottom());
//						}
//					}
//					
//					graph.addKey(overlapMidpoint);
//					graph.addKey(h1Enter);
//					graph.addKey(h2Enter);
//					
//					// connect midpoint to room
//					graph.addConnection(h1Enter, new Edge<Vector2>(h1Enter, overlapMidpoint,
//							h1Enter.dst(overlapMidpoint)));
//					graph.addConnection(overlapMidpoint, new Edge<Vector2>(overlapMidpoint, h1Enter,
//							h1Enter.dst(overlapMidpoint)));
//					
//					graph.addConnection(h2Enter, new Edge<Vector2>(h2Enter, overlapMidpoint,
//							h2Enter.dst(overlapMidpoint)));
//					graph.addConnection(overlapMidpoint, new Edge<Vector2>(overlapMidpoint, h2Enter,
//							h2Enter.dst(overlapMidpoint)));
					
					
					
//					if (!graph.hasKey(h1.getCenter())) {
//						graph.addKey(h1.getCenter());
//					}
//					graph.addConnection(h1.getCenter(), new Edge<Vector2>(h1.getCenter(), overlapMidpoint,
//							h1.getCenter().dst(overlapMidpoint)));
//					graph.addConnection(overlapMidpoint, new Edge<Vector2>(overlapMidpoint, h1.getCenter(),
//							h1.getCenter().dst(overlapMidpoint)));
//					
//					if (!graph.hasKey(h2.getCenter())) {
//						graph.addKey(h2.getCenter());
//					}
//					graph.addConnection(h2.getCenter(), new Edge<Vector2>(h2.getCenter(), overlapMidpoint,
//							h2.getCenter().dst(overlapMidpoint)));
//					graph.addConnection(overlapMidpoint, new Edge<Vector2>(overlapMidpoint, h2.getCenter(),
//							h2.getCenter().dst(overlapMidpoint)));
					
//				}
//			}
		}
		
		
		return graph;
	}
	
	private DGraph<Room> createDungeonGraph() {
		/*
		 * Rooms connect iff a hall passes through them
		 * a hall is a room
		 */
		DGraph<Room> graph = new DGraph<Room>();
		// because a hallway will always connect two rooms, we use this as a reference for graph building
		for (Room hall : this.getHalls()) {
			for (Room room : this.getDungeon()) {
				if (room.getType() != RoomType.HALLWAY) { // this also implicitly checks that hall != room
					if (hall.touches(room)) {
						float dist = hall.getCenter().dst(room.getCenter());						
						if (!graph.hasKey(hall)) {
							graph.addKey(hall);
						}
						graph.addConnection(hall, new Edge<Room>(hall, room, dist));
						
						if (!graph.hasKey(room)) {
							graph.addKey(room);
						}
						graph.addConnection(room, new Edge<Room>(room, hall, dist));
					}
				}
			}
		}
		
		for (int i=0; i < getHalls().size; i++) {
			for (int j=i+1; j < getHalls().size; j++) {
				Room h1 = getHalls().get(i);
				Room h2 = getHalls().get(j);
				
				if (h1.touches(h2)) {
					float dist = h1.getCenter().dst(h2.getCenter());
					if (!graph.hasKey(h1)) {
						graph.addKey(h1);
					}
					graph.addConnection(h1, new Edge<Room>(h1, h2, dist));
					
					if (!graph.hasKey(h2)) {
						graph.addKey(h2);
					}
					graph.addConnection(h2, new Edge<Room>(h2, h1, dist));
				}
			}
		}
		
		return graph;
	}
	
	
	ShapeRenderer sr = new ShapeRenderer();
	private boolean displayFullGraph = false;
	/*
	 * DEBUG RENDERING!
	 */
	public void render(Matrix4 combined) {
		sr.setProjectionMatrix(combined);
	    sr.begin(ShapeRenderer.ShapeType.Filled);
	    
	    
	    sr.setColor(Color.BROWN);
	    for (Room corridor : getCriticalRooms()) {
	    	sr.rect(corridor.getLeft(), corridor.getBottom(), corridor.getWidth(), corridor.getHeight());
	    }
	    sr.setColor(Color.SALMON);
		for (Room rooms : getOptionalRooms()) {
			sr.rect(rooms.getLeft(), rooms.getBottom(), rooms.getWidth(), rooms.getHeight());
		}
		
		// will draw start and end rooms twice, but it's ok to overlap
		sr.setColor(Color.LIGHT_GRAY);
		sr.rect(startRoom.getLeft(), startRoom.getBottom(), startRoom.getWidth(), startRoom.getHeight());
		sr.setColor(Color.GOLD);
		sr.rect(endRoom.getLeft(), endRoom.getBottom(), endRoom.getWidth(), endRoom.getHeight());
		
		sr.end();
		sr.begin(ShapeRenderer.ShapeType.Line);
		
		
		sr.setColor(Color.RED);
		for (Room halls : getHalls()) {
			sr.rect(halls.getLeft(), halls.getBottom(), halls.getWidth(), halls.getHeight());
		}
		
		// DRAW SPATIAL PARTITION
//		sr.setColor(Color.BLACK);
//		Keys keys = spatialPartition.keys();
//		while (keys.hasNext) {
//			int i = keys.next();
//			int x = (i % PARTITION_WIDTH) * UNITS_PER_PARTITION, y = (i / PARTITION_WIDTH) * UNITS_PER_PARTITION;
//			sr.rect(x, y, UNITS_PER_PARTITION, UNITS_PER_PARTITION);
//		}
		
		
		if (Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.T)) {
			displayFullGraph = !displayFullGraph;
		}
		
		if (displayFullGraph) {
			// DRAW CRITICAL GRAPH
			sr.setColor(Color.CYAN);
//			for (Room start : criticalRoomGraph.getKeys()) {
//				for (Connection<Room> endC : criticalRoomGraph.getConnections(start)) {
//					Room end = endC.getToNode() == start ? endC.getFromNode() : endC.getToNode();
//					sr.line(start.getCenterX(), start.getCenterY(), end.getCenterX(), end.getCenterY());
//				}
//			}
			
			for (Vector2 start : hallwayGraph.getKeys()) {
				for (Connection<Vector2> endC : hallwayGraph.getConnections(start)) {
					sr.line(start, endC.getToNode());
				}
//				sr.circle(start.x, start.y, 1.0f);
			}
			for (Vector2 debugPoints : points) {
				sr.circle(debugPoints.x, debugPoints.y, 1.0f);
			}
		} else {
			// DRAW DUNGEON GRAPH
			sr.setColor(Color.BLACK);
			for (Room room : dungeonGraph.getKeys()) {
				for (Connection<Room> edge : dungeonGraph.getConnections(room)) {
					Room a = edge.getToNode();
					Room b = edge.getFromNode();
					sr.line(a.getCenterX(), a.getCenterY(), b.getCenterX(), b.getCenterY());
				}
			}
		}
	    
		// DRAW MIDPOINTS OF ROOMS
		
		sr.setColor(Color.BLACK);
		for (Room room : this.getDungeon()) {
			sr.circle(room.getCenterX(), room.getCenterY(), 3.0f);
		}
		
		sr.end();
	}
	
	/*
	 * Creates a spatial partition, where world cords / unitsPerPartition map to the rooms.
	 * Make sure dungeon is in world space before calling this method.
	 */
	private IntMap<Array<Room>> createSpatialParition() {
		IntMap<ObjectSet<Room>> map = new IntMap<ObjectSet<Room>>(); // no repeats
		
		/*
		 * HORRIBLE RUNTIME. But only needs to be run once per dungeon generation.
		 * It feels like x and y could increment by unitsPerPartition each time, but this produces weird results
		 */
		for (Room room : this.getDungeon()) {
			for (int y=room.getBottom(); y <= room.getTop(); y++) {
				for (int x=room.getLeft(); x <= room.getRight(); x++) {
					int key = calculatePartitionKey(x, y);
					if (map.containsKey(key)) {
						map.get(key).add(room);
					} else {
						map.put(key, new ObjectSet<Room>());
					}
				}
			}
		}
		
		IntMap<Array<Room>> ret = new IntMap<Array<Room>>();
		Keys keys = map.keys();
		while (keys.hasNext) {
			int key = keys.next();
			ret.put(key, new Array<Room>());
			for (Room room : map.get(key)) {
				ret.get(key).add(room);
			}
//			map.keys().remove();
		}
		return ret;
	}
	
	ObjectSet<Room> getPotentialRoomsInArea(Rectangle area) {
		int biggest = max(HALL_WIDTH, MIN_SIDE_LENGTH);
		if (area.width > biggest || area.height > biggest) {
//			try {
//				throw new Exception();
//			} catch (Exception e) {
//				System.out.println("WARNING: Area -> Room algorithm has no case for entities larger than rooms");
//				e.printStackTrace();
//			}
//			Gdx.app.error("getPotentialRoomsInArea",
//					"WARNING: Area -> Room algorithm has no case for entities larger than rooms", new IllegalArgumentException());
		}
		
		// create a list of rooms that area could potentially have from spatial partition
		ObjectSet<Room> potentialRooms = new ObjectSet<Room>(ESTIMATED_MAX_ROOMS_PER_PARTITION);
		
		Integer aa = calculatePartitionKey(area.x, area.y);
		Integer bb = calculatePartitionKey(area.x + area.width, area.y);
		Integer cc = calculatePartitionKey(area.x, area.y + area.height);
		Integer dd = calculatePartitionKey(area.x + area.width, area.y + area.height);
		
		// no repeat rooms thanks to hashset
		if (spatialPartition.containsKey(aa)) {
			potentialRooms.addAll(spatialPartition.get(aa));
		} else if (aa != bb) {
			if (spatialPartition.containsKey(bb)) {
				potentialRooms.addAll(spatialPartition.get(bb));
			} else if (bb != cc) {
				if (spatialPartition.containsKey(cc)) {
					potentialRooms.addAll(spatialPartition.get(cc));
				} else if (cc != dd) {
					if (spatialPartition.containsKey(dd)) {
						potentialRooms.addAll(spatialPartition.get(dd));
					}
				}
			}
		} else {
			if (bb != cc) {
				if (spatialPartition.containsKey(cc)) {
					potentialRooms.addAll(spatialPartition.get(cc));
				} else if (cc != dd) {
					if (spatialPartition.containsKey(dd)) {
						potentialRooms.addAll(spatialPartition.get(dd));
					}
				}
			} else {
				if (cc != dd) {
					if (spatialPartition.containsKey(dd)) {
						potentialRooms.addAll(spatialPartition.get(dd));
					}
				}
			}
		}
		return potentialRooms;
	}
	

	public Array<Room> getRoomsInArea(Rectangle area) {
		
		int biggest = max(HALL_WIDTH, MIN_SIDE_LENGTH);
		ObjectSet<Room> potentialRooms = null;
		/*
		 * If the area we're looking for is bigger than that of the spatial partition we need to subdivide it
		 */
		if (area.width > biggest || area.height > biggest) {
//			Gdx.app.error("getRoomsInArea", "areas larger than UNITS_PER_PARTITION might have problems");
			int sizeFactor = (max(1, (int)area.width/UNITS_PER_PARTITION))*max(1, (int)area.height/UNITS_PER_PARTITION);
			potentialRooms = new ObjectSet<Room>(ESTIMATED_MAX_ROOMS_PER_PARTITION*sizeFactor);
			
//			potentialRooms = new ObjectSet<Room>();
			Rectangle r = new Rectangle(0.0f, 0.0f, UNITS_PER_PARTITION, UNITS_PER_PARTITION); // not problem?
			
			for (float y=area.y; y <= area.y + area.height; y += this.UNITS_PER_PARTITION) { // not problem
				for (float x=area.x; x <= area.x + area.width; x += this.UNITS_PER_PARTITION) { // not problem
					r.setX(x);
					r.setY(y);
					
//					ObjectSet<Room> pRooms = this.getPotentialRoomsInArea(r);
					Array<Room> pRooms = this.getAllPotentialRoomsAtPoint(x, y);
					if (pRooms != null) {
						potentialRooms.addAll(pRooms);
					}
				}
			}
						
		} else {
			/*
			 * If the area we're looking for is smaller then we use 4 corners
			 */
			// create a list of rooms that area could potentially have from spatial partition
			potentialRooms = this.getPotentialRoomsInArea(area);
			
		}
		
			// perform a bounds check on the potential rooms
			Array<Room> rooms = new Array<Room>(potentialRooms.size);
			for (Room room : potentialRooms) {			
				if (room.getBounds().overlaps(area)) {
					rooms.add(room);
				}
			}
			
			return rooms;
	}
	
	/*
	 * Returns a list of rooms that could potentially be in our area from our spatial partition
	 */
	public Array<Room> getRoomsContainingArea(Rectangle area) {
		// create a list of rooms that area could potentially have from spatial partition
		ObjectSet<Room> potentialRooms = this.getPotentialRoomsInArea(area);
			
		// perform a bounds check on the potential rooms
		Array<Room> rooms = new Array<Room>(potentialRooms.size);
		for (Room room : potentialRooms) {			
			if (room.getBounds().contains(area)) {
				rooms.add(room);
			}
		}
		
		return rooms;
	}
	
	public Room getRoomAtPoint(Vector2 point, RoomType type) {
		Array<Room> rooms = spatialPartition.get(calculatePartitionKey(point.x, point.y));
		if (rooms != null) {
			for (Room room : rooms) {
				if (room.getBounds().contains(point) && room.getType() == type) {
					return room;
				}
			}
		}
		return null;
	}
	
	public Array<Room> getAllRoomsAtPoint(Vector2 point) {
		Array<Room> rooms = spatialPartition.get(calculatePartitionKey(point.x, point.y));
		if (rooms != null) {
			for (Room room : rooms) {
				if (!room.getBounds().contains(point)) {
					rooms.removeValue(room, true);
				}
			}
		}
		return rooms;
	}
	
	public Array<Room> getAllPotentialRoomsAtPoint(Vector2 point) {
		return getAllPotentialRoomsAtPoint(point.x, point.y);
	}
	
	public Array<Room> getAllPotentialRoomsAtPoint(int x, int y) {
		return spatialPartition.get(calculatePartitionKey(x, y)); // don't call other method, this is faster
	}
	
	public Array<Room> getAllPotentialRoomsAtPoint(float x, float y) {
		return spatialPartition.get(calculatePartitionKey(x, y));
	}
	
	
	private int calculateWidth() {
		int leftmostWall = Integer.MAX_VALUE;
		int rightmostWall = Integer.MIN_VALUE;
		for (Room room : this.getDungeon()) {
			if (room.getLeft() < leftmostWall) {
				leftmostWall = room.getLeft();
			}
			if (room.getRight() > rightmostWall) {
				rightmostWall = room.getRight();
			}
		}
		return rightmostWall - leftmostWall;
	}
	
	private int calculateHeight() {
		int bottomWall = Integer.MAX_VALUE;
		int topWall = Integer.MIN_VALUE;
		for (Room room : this.getDungeon()) {
			if (room.getBottom() < bottomWall) {
				bottomWall = room.getBottom();
			}
			if (room.getTop() > topWall) {
				topWall = room.getTop();
			}
		}
		return topWall - bottomWall;
	}
	
	public Integer calculatePartitionKey(float x, float y) {
		int px = (int)x / UNITS_PER_PARTITION, py = (int)y / UNITS_PER_PARTITION;
		return px + py * PARTITION_WIDTH;
	}
	
	public Integer calculatePartitionKey(int x, int y) {
		int px = x / UNITS_PER_PARTITION, py = y / UNITS_PER_PARTITION;
		return px + py * PARTITION_WIDTH;
	}
	
	private int min(int a, int b) {
		return a < b ? a : b;
	}
	
	private int max(int a, int b) {
		return a > b ? a : b;
	}
	
}
