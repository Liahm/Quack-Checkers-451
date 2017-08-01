import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

class Checkers
{
	public static void main(String[] args)
	{
		try
		{
			Socket s = new Socket("mc.drexel.rocks", 28000);
			BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
			PrintWriter writer = new PrintWriter(s.getOutputStream(), true);
			
			writer.println("a");
			System.out.println(reader.readLine());
			writer.println("duck");
			System.out.println(reader.readLine());
			writer.println("test");
			System.out.println(reader.readLine());
			
			Thread.sleep(15000);
			
			writer.println("delayed");
			System.out.println(reader.readLine());
			
			s.close();
		}
		catch(Throwable e)
		{
			e.printStackTrace();
		}
	}
}

class CheckersServer
{
	private final ServerSocket ss;
	private final List<Player> lobby; //players not in games
	private final List<Game> games; //list of games looking for players
	private boolean lobbyUpdate = false;
	//private Object lobbyUpdateLock = new Object();
	
	public static void main(String[] args)
	{
		try
		{
			new CheckersServer().start();
		}
		catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	private CheckersServer() throws IOException
	{
		ss = new ServerSocket(28000);
		lobby = new ArrayList<Player>();
		games = new ArrayList<Game>();
	}
	
	private void start()
	{
		while(true)
		{
			new Thread(new MatchMaker()).start();
			try
			{
				//accept connections and set up new player threads
				System.out.println("waiting for connection " + ss);
				Socket s = ss.accept();
				Player p = new Player(s);
				new Thread(p).start();
				lobby.add(p);
			}
			catch(IOException e)
			{
				e.printStackTrace();
			}
		}
	}
	
	private class MatchMaker implements Runnable
	{
		public void run()
		{
			while(true)
			{
				synchronized(lobby)
				{
					//loop through players and read their commands
					for(Player p : lobby)
					{
						readLoop: for(String s = p.read(); s != null; s = p.read())
						{
							//check if the player exits
							if(s.equals("exit"))
							{
								lobby.remove(p);
								break;
							}
							else
								if(s.equals("create"))
								{
									lobbyUpdate = true;
									lobby.remove(p);
									Game g = new Game();
									games.add(g);
									g.addPlayer(p);
									new Thread(g).start(); //start handling input from the player while waiting for a second player
									break;
								}
								else
									if(s.equals("join") && games.size() > 0)
									{
										lobbyUpdate = true;
										lobby.remove(p);
										Game g = games.get((int)Math.floor(Math.random() * games.size())); //select a random game
										games.remove(g);
										g.addPlayer(p); //game will automatically start with 2 players
										break;
									}
									else
										if(s.matches("^join \\w{1,8}$"))
										{
											String name = s.substring(5);
											
											for(Game g : games)
												if(g.getName().equals(name))
												{
													lobbyUpdate = true;
													lobby.remove(p);
													games.remove(g);
													g.addPlayer(p); //game will start with 2 players
													break readLoop;
												}
										}
						}
					}
					
					//synchronized(lobbyUpdateLock)
					//{
						if(lobbyUpdate)
						{
							//list of available commands
							String state = "create|join|join ";
							if(games.isEmpty())
								state += "<EMPTY>";
							else
							{
								state += games.get(0).getName();
								for(int i = 1; i < games.size(); i++)
									state += "," + games.get(i).getName();
							}
							state += "|exit";
							
							//write the state to everybody in the lobby
							for(Player p : lobby)
								p.write(state);
						}
						
						//reset update
						lobbyUpdate = false;
					//}
				}
				
				//sleep for a bit
				try
				{
					Thread.sleep(100);
				}
				catch(InterruptedException e)
				{
					e.printStackTrace();
				}
			}
		}
	}
	
	private class Player implements Runnable
	{
		private Socket socket;
		private BufferedReader reader;
		private PrintWriter writer;
		private Queue<String> messages;
		private String name;
		
		public Player(Socket s) throws IOException
		{
			socket = s;
			reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
			writer = new PrintWriter(s.getOutputStream(), true);
			messages = new LinkedList<String>();
			name = null; //s.toString();
		}
		
		public synchronized void write(String s)
		{
			writer.println(s);
		}
		
		public synchronized String read()
		{
			return messages.poll();
		}
		
		public synchronized String getName()
		{
			return name;
		}
		
		public void run()
		{
			try
			{
				String line = reader.readLine();
				synchronized(this)
				{
					//set the name
					name = "";
					
					for(int i = 0; i < line.length() && name.length() < 8; i++)
						name += ("" + line.charAt(i)).matches("\\w") ? line.charAt(i) : "";
					
					if(name.length() == 0)
						name = "nameless";
				}
				for(line = reader.readLine(); line != null && !line.equals("exit"); line = reader.readLine())
					messages.add(line);
			}
			catch(IOException e)
			{
				e.printStackTrace();
			}
			messages.add("exit");
		}
	}
	
	private class Game implements Runnable
	{
		private Player a;
		private Player b;
		private Board board;
		private String name;
		
		public Game()
		{
			board = new Board();
		}
		
		private class Board
		{
			public static final byte EMPTY = 0; //000
			public static final byte WHITE = 4; //100 first 1 is to show non-empty
			public static final byte BLACK = 5; //101
			public static final byte KING = 2; //010 (also used as invalid)
			
			private byte[][] board;
			boolean turn; //false white, true black
			int last; //last turn, if was a jump, used to force multiple jumps
			
			public Board()
			{
				board = new byte[8][8];
				for(int y = 0; y < 8; y++)
					for(int x = (y & 1) == 0 ? 1 : 0; x < 8; x += 2) //every other tile, alternates even/odd to get the checkerboard pattern
						board[x][y] = y < 4 ? BLACK : y > 5 ? WHITE : KING; //y=0 is the top, black is top white is bottom (like in chess), off tiles are marked invalid with KING
				
				turn = true; //black is first to move for some reason, should be white like chess
				last = 0;
			}
			
			public int get(int x, int y)
			{
				if(x < 0 || x >= 8 || y < 0 || y >= 8)
					return KING; //invalid location
				return board[x][y];
			}
			
			public byte getColor(int x, int y)
			{
				if((get(x, y) & BLACK) == BLACK)
					return BLACK;
				if((get(x, y) & WHITE) == WHITE)
					return WHITE;
				return KING;
			}
			
			public boolean isKing(int x, int y)
			{
				return get(x, y) != KING && (get(x, y) & KING) == KING;
			}
			
			public List<Integer> getMoves()
			{
				List<Integer> moves = new ArrayList<Integer>();
				
				if(last != 0) //check for double jumping
				{
					int x = last % 8;
					int y = (last >> 3) % 8;
					
					if(turn)
					{
						if(getColor(x, y) == WHITE)
						{
							//forward
							if(getColor(x - 1, y - 1) == BLACK && get(x - 2, y - 2) == EMPTY)
								moves.add(x + (y << 3) + (x - 2 << 6) + (y - 2 << 9));
							if(getColor(x + 1, y - 1) == BLACK && get(x + 2, y - 2) == EMPTY)
								moves.add(x + (y << 3) + (x + 2 << 6) + (y - 2 << 9));
							
							//backward
							if(isKing(x, y) && getColor(x - 1, y + 1) == BLACK && get(x - 2, y + 2) == EMPTY)
								moves.add(x + (y << 3) + (x - 2 << 6) + (y + 2 << 9));
							if(isKing(x, y) && getColor(x + 1, y + 1) == BLACK && get(x + 2, y + 2) == EMPTY)
								moves.add(x + (y << 3) + (x + 2 << 6) + (y + 2 << 9));
						}
					}
					else
						if(getColor(x, y) == BLACK)
						{
							//backward
							if(isKing(x, y) && getColor(x - 1, y - 1) == WHITE && get(x - 2, y - 2) == EMPTY)
								moves.add(x + (y << 3) + (x - 2 << 6) + (y - 2 << 9));
							if(isKing(x, y) && getColor(x + 1, y - 1) == WHITE && get(x + 2, y - 2) == EMPTY)
								moves.add(x + (y << 3) + (x + 2 << 6) + (y - 2 << 9));
							
							//forward
							if(getColor(x - 1, y + 1) == WHITE && get(x - 2, y + 2) == EMPTY)
								moves.add(x + (y << 3) + (x - 2 << 6) + (y + 2 << 9));
							if(getColor(x + 1, y + 1) == WHITE && get(x + 2, y + 2) == EMPTY)
								moves.add(x + (y << 3) + (x + 2 << 6) + (y + 2 << 9));
						}
				}
				else //calculate all available jumps
				{
					for(int x = 0; x < 8; x++)
						for(int y = 0; y < 8; y++)
							if(turn)
							{
								if(getColor(x, y) == WHITE)
								{
									//forward jumps
									if(getColor(x - 1, y - 1) == BLACK && get(x - 2, y - 2) == EMPTY)
										moves.add(x + (y << 3) + (x - 2 << 6) + (y - 2 << 9));
									if(getColor(x + 1, y - 1) == BLACK && get(x + 2, y - 2) == EMPTY)
										moves.add(x + (y << 3) + (x + 2 << 6) + (y - 2 << 9));
									
									//backward jumps
									if(isKing(x, y) && getColor(x - 1, y + 1) == BLACK && get(x - 2, y + 2) == EMPTY)
										moves.add(x + (y << 3) + (x - 2 << 6) + (y + 2 << 9));
									if(isKing(x, y) && getColor(x + 1, y + 1) == BLACK && get(x + 2, y + 2) == EMPTY)
										moves.add(x + (y << 3) + (x + 2 << 6) + (y + 2 << 9));
								}
							}
							else
								if(getColor(x, y) == BLACK)
								{
									//backward jumps
									if(isKing(x, y) && getColor(x - 1, y - 1) == WHITE && get(x - 2, y - 2) == EMPTY)
										moves.add(x + (y << 3) + (x - 2 << 6) + (y - 2 << 9));
									if(isKing(x, y) && getColor(x + 1, y - 1) == WHITE && get(x + 2, y - 2) == EMPTY)
										moves.add(x + (y << 3) + (x + 2 << 6) + (y - 2 << 9));
									
									//forward jumps
									if(getColor(x - 1, y + 1) == WHITE && get(x - 2, y + 2) == EMPTY)
										moves.add(x + (y << 3) + (x - 2 << 6) + (y + 2 << 9));
									if(getColor(x + 1, y + 1) == WHITE && get(x + 2, y + 2) == EMPTY)
										moves.add(x + (y << 3) + (x + 2 << 6) + (y + 2 << 9));
								}
					if(moves.isEmpty()) //if no jumps are forced, then look at the remaining moves
						for(int x = 0; x < 8; x++)
							for(int y = 0; y < 8; y++)
								if(turn)
								{
									if(getColor(x, y) == WHITE)
									{
										//forward
										if(get(x - 1, y - 1) == EMPTY)
											moves.add(x + (y << 3) + (x - 1 << 6) + (y - 1 << 9));
										if(get(x + 1, y - 1) == EMPTY)
											moves.add(x + (y << 3) + (x + 1 << 6) + (y - 1 << 9));
										
										//backward
										if(isKing(x, y) && get(x - 1, y + 1) == EMPTY)
											moves.add(x + (y << 3) + (x - 1 << 6) + (y + 1 << 9));
										if(isKing(x, y) && get(x + 1, y + 1) == EMPTY)
											moves.add(x + (y << 3) + (x + 1 << 6) + (y + 1 << 9));
									}
								}
								else
									if(getColor(x, y) == BLACK)
									{
										//backward
										if(isKing(x, y) && get(x - 1, y - 1) == EMPTY)
											moves.add(x + (y << 3) + (x - 1 << 6) + (y - 1 << 9));
										if(isKing(x, y) && get(x + 1, y - 1) == EMPTY)
											moves.add(x + (y << 3) + (x + 1 << 6) + (y - 1 << 9));
										
										//forward
										if(get(x - 1, y + 1) == EMPTY)
											moves.add(x + (y << 3) + (x - 1 << 6) + (y + 1 << 9));
										if(get(x + 1, y + 1) == EMPTY)
											moves.add(x + (y << 3) + (x + 1 << 6) + (y + 1 << 9));
									}
				}
				
				return moves;
			}
			
			public void doMove(int move)
			{
				//do the move
				if(getMoves().contains(move)) //is a valid move
				{
					//figure out what the move actually is
					int x1 = move % 8;
					int y1 = (move >> 3) % 8;
					int x2 = (move >> 6) % 8;
					int y2 = (move >> 9) % 8;
					boolean king = isKing(x1, y1) || y2 == 0 || y2 == 7; //is a king already, or will become a king
					
					//remove anything at the start and inbetween (for jumps)
					board[x1][y1] = EMPTY;
					board[(x1 + x2) / 2][(y1 + y2) / 2] = EMPTY;
					
					//put the peice where it moved to
					board[x2][y2] = (byte)((turn ? BLACK : WHITE) | (king ? KING : EMPTY));
					
					//reset the last jump position
					last = 0;
					
					if(x1 + 1 < x2 || x1 - 1 > x2) //it was a jump
					{
						last = x2 + (y2 << 3); //set the jump position
						if(getMoves().isEmpty()) //can't do more jumps
						{
							last = 0; //reset the last jump position
							turn = !turn; //flip the turn and continue
						}
					}
					else //just a normal move, flip the turn
						turn = !turn;
				}
			}
			
			public void update(Player a, Player b)
			{
				
			}
		}
		
		public boolean addPlayer(Player p)
		{
			if(a == null && b == null && (name = p.getName()) != null) //randomly put in the first player and set the lobby name
				if(Math.random() < 0.5)
					a = p;
				else
					b = p;
			else
				if(a == null) //fill in the second player
					a = p;
				else
					if(b == null)
						b = p;
					else
						return false; //return false if both slots were full
			
			return true;
		}
		
		public String getName()
		{
			return name;
		}
		
		public void run()
		{
			while(true) //run until there are two players, then let the game start
			{
				//handle input
				synchronized(lobby) //make sure only this can be modifying the lobby
				{
					//check if there are two players
					if(a != null && b != null)
						break; //start the game
					
					Player p = a != null ? a : b != null ? b : null;
					
					if(p == null) //no players, that's probably bad, shouldn't be possible
					{
						games.remove(this); //make sure the empty game is deleted
						return; //kill this thread
					}
					
					for(String s = p.read(); s != null; s = p.read())
						if(s.equals("back"))
						{
							lobby.add(p); //return p to the lobby
							games.remove(this);
							lobbyUpdate = true;
							return; //kill this thread
						}
						else
							if(s.equals("exit"))
							{
								games.remove(this);
								lobbyUpdate = true;
								return; //kill this thread
							}
							else
								if(s.equals("black")) //black moves first
								{
									a = p;
									b = null;
								}
								else
									if(s.equals("red"))
									{
										b = p;
										a = null;
									}
				}
				
				//sleep for a bit
				try
				{
					Thread.sleep(100);
				}
				catch(InterruptedException e)
				{
					e.printStackTrace();
				}
			}
			while(true) //game running loop
			{
				board.update(a, b);
			}
		}
	}
}