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
	String url = "mc.drexel.rocks";
	int port = 28000;
	Queue<String> messages, inputs;
	PrintWriter writer;
	
	public static void main(String[] args)
	{
		new Checkers(args).start();
	}
	
	private Checkers(String[] args)
	{
		try
		{
			if(args.length > 0)
			{
				url = args[0].split(":")[0];
				if(args[0].split(":").length > 1)
					port = Integer.parseInt(args[0].split(":")[1]);
			}
			
			Socket s = new Socket(url, port);
			
			messages = new LinkedList<String>();
			inputs = new LinkedList<String>();
			writer = new PrintWriter(s.getOutputStream(), true);
			
			//read user input
			new Thread(new Runnable()
			{
				public void run()
				{
					BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
					try
					{
						for(String line = reader.readLine(); line != null; line = reader.readLine())
							synchronized(messages)
							{
								inputs.add(line);
							}
					}
					catch(IOException e)
					{
						e.printStackTrace();
					}
				}
			}).start();
			
			//read server messages
			new Thread(new Runnable()
			{
				public void run()
				{
					try
					{
						BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
						for(String line = reader.readLine(); line != null; line = reader.readLine())
							synchronized(messages)
							{
								messages.add(line);
							}
					}
					catch(IOException e)
					{
						e.printStackTrace();
					}
				}
			}).start();
			
			//s.close();
		}
		catch(Throwable e)
		{
			e.printStackTrace();
		}
	}
	
	private void start()
	{
		//read the username and send it to the server
		System.out.print("Username: ");
		
		String username = null;
		while(username == null)
			username = readInput();
		
		write(username);
		
		//process and show server messages
		String message = null;
		String input = null;
		while(true)
		{
			//process server messages
			while((message = read()) != null)
			{
				if(message.startsWith("@")) //it's a game state
				{
					//@<player>|<board> <turn>|<commands>
					//@b|32chars b|<commands>, commands start at 38, turn is char 37
					boolean black = message.charAt(1) == 'b';
					boolean turn = message.charAt(1) == message.charAt(36);
					String commands = message.substring(38);
					String board = message.substring(3, 35);
					
					//set up the display
					String[] display = new String[10];
					for(int i = 0; i < 8; i++)
						display[i] = "" + (8 - i);
					display[8] = " ABCDEFGH";
					display[9] = "Commands: " + commands;
					
					//add the board to the display
					int i = 0;
					for(int y = 0; y < 8; y++)
						for(int x = 0; x < 8; x++)
							display[y] += (x & 1) == (y & 1) ? " " : board.charAt(i++);
					
					//add the player color
					display[0] += black ? " You are black." : " You are white.";
					
					//add turn/who won
					display[1] += turn ? " It is your turn." : message.charAt(37) == 'B' ? " Black won." : message.charAt(37) == 'W' ? " White won." : "";
					
					//print the text
					for(i = 0; i < display.length; i++)
						System.out.println(display[i]);
				}
				else //not a game state
					System.out.println("Commands: " + message);
			}
			
			//process user input
			while((input = readInput()) != null)
			{
				//just send it to the server
				write(input);
				if(input.equals("exit"))
					System.exit(0);
			}
			
			//sleep for a bit
			try
			{
				Thread.sleep(100);
			}
			catch(Throwable e)
			{
				e.printStackTrace();
			}
		}
	}
	
	private String read()
	{
		synchronized(messages)
		{
			return messages.poll();
		}
	}
	
	public void write(String s)
	{
		writer.println(s);
	}
	
	public String readInput()
	{
		synchronized(inputs)
		{
			return inputs.poll();
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
			(args.length > 0 ? new CheckersServer(Integer.parseInt(args[0])) : new CheckersServer()).start();
		}
		catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	private CheckersServer() throws IOException
	{
		this(28000);
	}
	
	private CheckersServer(int port) throws IOException
	{
		ss = new ServerSocket(port);
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
				synchronized(lobby)
				{
					lobby.add(p);
					lobbyUpdate = true;
				}
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
					for(int i = lobby.size() - 1; i >= 0; i--)
					{
						Player p = lobby.get(i);
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
									Game g = new Game(p);
									games.add(g);
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
											String name = s.substring(5) + "'s Game";
											
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
					synchronized(this)
					{
						System.out.println(getName() + ": " + line);
						messages.add(line);
					}
				synchronized(this)
				{
					System.out.println(getName() + " exited");
					messages.add("exit");
				}
				socket.close();
			}
			catch(IOException e)
			{
				e.printStackTrace();
			}
		}
	}
	
	private class Game implements Runnable
	{
		private Player a;
		private Player b;
		private Board board;
		private String name;
		private boolean running;
		
		public Game(Player p)
		{
			board = new Board();
			addPlayer(p);
			name = p.getName() + "'s Game";
		}
		
		private class Board
		{
			public static final byte EMPTY = 0; //000
			public static final byte WHITE = 4; //100 first 1 is to show non-empty
			public static final byte BLACK = 5; //101
			public static final byte KING = 2; //010 (also used as invalid)
			
			private byte[][] board;
			private boolean turn; //false white, true black
			private int last; //last turn, if was a jump, used to force multiple jumps
			
			public Board()
			{
				board = new byte[8][8]; //[0,0] is invalid, [0,1] is black pawn, [7, 7] is invalid, [7, 6] is white pawn
				for(int y = 0; y < 8; y++)
					for(int x = 0; x < 8; x ++)
						board[x][y] = (x + y & 1) == 0 ? KING : y < 3 ? BLACK : y > 4 ? WHITE : EMPTY; //y=0 is the top, black is top white is bottom (like in chess), off tiles are marked invalid with KING
				
				turn = true; //black is first to move for some reason, should be white like chess
				last = 0;
			}
			
			public byte get(int x, int y)
			{
				if(x < 0 || x >= 8 || y < 0 || y >= 8)
					return KING; //invalid location
				return board[x][y];
			}
			
			public byte getColor(int x, int y)
			{
				byte piece = get(x, y);
				if((piece & BLACK) == BLACK)
					return BLACK;
				if((piece & WHITE) == WHITE)
					return WHITE;
				return piece; //KING if invalid, EMPTY if empty
			}
			
			public boolean isKing(int x, int y)
			{
				return get(x, y) != KING && (get(x, y) & KING) == KING;
			}
			
			public List<Integer> getMoves() //0x20(destY)(destX)(sourY)(sourX)
			{
				List<Integer> moves = new ArrayList<Integer>();
				
				if(!running) //if game is over just return no moves
					return moves;
				
				if(last != 0) //check for double jumping
				{
					int x = last & 7;
					int y = (last >> 3) & 7;
					
					if(!turn)
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
							if(!turn)
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
								if(!turn)
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
			
			public boolean doMove(int move) //returns if the move was valid
			{
				System.out.println("attempting move: " + move);
				String m = "";
				for(int i : getMoves())
					m += i + ",";
				System.out.println("valid moves: " + m);
				if(!getMoves().contains(move)) //isn't a valid move
					return false;
				
				//figure out what the move actually is
				int x1 = move & 7;
				int y1 = (move >> 3) & 7;
				int x2 = (move >> 6) & 7;
				int y2 = (move >> 9) & 7;
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
				
				return true;
			}
			
			public void update(Player A, Player B) //@<player>|<board> <turn>|commands
			{
				//@ designates the game is being played, b/w tells the player which side they are
				String aBase = "@b|";
				String bBase = "@w|";
				String base = "";
				
				//pack the board
				String[] decode = {".", "1", "2", "3", "w", "b", "W", "B"}; //123 shouldn't be used
				for(int y = 0; y < 8; y++)
					for(int x = y & 1 ^ 1; x < 8; x += 2)
						base += decode[get(x, y)];
				
				//add the turn
				base += " " + (getMoves().size() > 0 ? turn ? "b|" : "w|" : turn ? "W|" : "B|"); //if black is out of moves, white wins
				
				//list of possible moves
				String[] rows = {"8", "7", "6", "5", "4", "3", "2", "1"};
				String[] cols = {"A", "B", "C", "D", "E", "F", "G", "H"};
				List<Integer> movesList = getMoves();
				String moves = "";
				System.out.println(movesList.size());
				
				if(movesList.size() > 0)
					moves += cols[movesList.get(0) & 7] + rows[movesList.get(0) >> 3 & 7] + " " + cols[movesList.get(0) >> 6 & 7] + rows[movesList.get(0) >> 9 & 7];
				else
				{
					moves += "<EMPTY>";
					running = false; //no moves left, end the game
				}
				for(int i = 1; i < movesList.size(); i++)
					moves += "," + cols[movesList.get(i) & 7] + rows[movesList.get(i) >> 3 & 7] + " " + cols[movesList.get(i) >> 6 & 7] + rows[movesList.get(i) >> 9 & 7];
				moves += "|";
				
				System.out.println(moves);
				
				//base actions
				String actions = "quit|exit";
				
				//update a
				if(A != null)
					A.write(aBase + base + (turn ? moves : "") + actions);
				
				//update b
				if(B != null)
					B.write(bBase + base + (turn ? "" : moves) + actions);
			}
			
			public void process(Player A, Player B)
			{
				if(!turn) //black's turn, process black first
				{
					for(String s = B.read(); s != null; s = B.read())
						if(s.equals("quit"))
						{
							running = false;
							turn = true;
							b = null;
							synchronized(lobby)
							{
								lobby.add(B);
								lobbyUpdate = true;
							}
							break;
						}
						else
							if(s.equals("exit"))
							{
								running = false;
								turn = true;
								b = null;
								break;
							}
							else
								if(s.matches("[A-H][1-8] [A-H][1-8]"))
								{
									System.out.println("valid move format: " + s);
									String rows = "87654321";
									String cols = "ABCDEFGH";
									if(doMove(cols.indexOf(s.charAt(0)) + (rows.indexOf(s.charAt(1)) << 3) + (cols.indexOf(s.charAt(3)) << 6) + (rows.indexOf(s.charAt(4)) << 9)));
										update(A, B); //only update if the move happens
								}
					
					for(String s = A.read(); s != null; s = A.read())
						if(s.equals("quit"))
						{
							running = false;
							turn = false;
							a = null;
							synchronized(lobby)
							{
								lobby.add(A);
								lobbyUpdate = true;
							}
							break;
						}
						else
							if(s.equals("exit"))
							{
								running = false;
								turn = false;
								a = null;
								break;
							}
				}
				else //white's turn
				{
					for(String s = A.read(); s != null; s = A.read())
						if(s.equals("quit"))
						{
							running = false;
							turn = false;
							a = null;
							synchronized(lobby)
							{
								lobby.add(A);
								lobbyUpdate = true;
							}
							break;
						}
						else
							if(s.equals("exit"))
							{
								running = false;
								turn = false;
								a = null;
								break;
							}
							else
								if(s.matches("[A-H][1-8] [A-H][1-8]"))
								{
									System.out.println("valid move format: " + s);
									String rows = "87654321";
									String cols = "ABCDEFGH";
									if(doMove(cols.indexOf(s.charAt(0)) + (rows.indexOf(s.charAt(1)) << 3) + (cols.indexOf(s.charAt(3)) << 6) + (rows.indexOf(s.charAt(4)) << 9)));
										update(A, B); //only update if the move happens
								}
					
					for(String s = B.read(); s != null; s = B.read())
						if(s.equals("quit"))
						{
							running = false;
							turn = true;
							b = null;
							synchronized(lobby)
							{
								lobby.add(B);
								lobbyUpdate = true;
							}
							break;
						}
						else
							if(s.equals("exit"))
							{
								running = false;
								turn = true;
								b = null;
								break;
							}
				}
			}
		}
		
		public boolean addPlayer(Player p)
		{
			if(a == null && b == null && (name = p.getName()) != null) //randomly put in the first player and set the lobby name
			{
				if(Math.random() < 0.5)
					a = p;
				else
					b = p;
				
				//tell the player what commands are available
				p.write("black|red|quit|exit");
			}
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
						if(s.equals("quit"))
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
			
			//set the game to running
			running = true;
			
			//update the players with the initial board
			board.update(a, b);
			
			while(running) //game running loop
			{
				board.process(a, b);
				
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
			
			board.update(a, b);
			
			while(a != null || b != null) //post game screen
			{
				if(a != null)
					for(String s = a.read(); s != null; s = a.read())
						if(s.equals("quit"))
						{
							synchronized(lobby)
							{
								lobby.add(a);
								lobbyUpdate = true;
							}
							a = null;
							break;
						}
						else
							if(s.equals("exit"))
							{
								a = null;
								break;
							}
				
				if(b != null)
					for(String s = b.read(); s != null; s = b.read())
						if(s.equals("quit"))
						{
							synchronized(lobby)
							{
								lobby.add(b);
								lobbyUpdate = true;
							}
							b = null;
							break;
						}
						else
							if(s.equals("exit"))
							{
								b = null;
								break;
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
}