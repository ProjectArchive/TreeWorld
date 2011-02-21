package eu.MrSnowflake.android.gametemplate;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewConfiguration;
import android.view.GestureDetector.SimpleOnGestureListener;
import eu.MrSnowflake.android.gametemplate.GameTemplate.GameState;

/**
 * View that draws, takes keystrokes, etc. for a simple LunarLander game.
 * 
 * Has a mode which RUNNING, PAUSED, etc. Has a x, y, dx, dy, ... capturing the
 * current ship physics. All x/y etc. are measured with (0,0) at the lower left.
 * updatePhysics() advances the physics based on realtime. draw() renders the
 * ship, and does an invalidate() to prompt another draw() as soon as possible
 * by the system.
 */
class GameView extends SurfaceView implements SurfaceHolder.Callback {

	private static final float DISTANCE_DIP = 10.0f;
	private static final float PATH_DIP = 300.0f;
	private int minScaledVelocity = ViewConfiguration.get(this.getContext()).getScaledMinimumFlingVelocity();
	private GestureDetector detector = new GestureDetector( new SwipeDetector() );
	
	@Override
	public boolean onTouchEvent(MotionEvent event) {
	detector.onTouchEvent(event);
	return true;
	}
	
	class GameThread extends Thread {
		/*
		 * State-tracking constants
		 */
		TreeNode root; //the current root, the first node seen on screen
		TreeNode previousRoot; // the previous root, kept track of for drawing purposes.
		Point previousRootRootLocation; //the point from previousRoot's parent, used for drawing purposes.
		//TreeNode previousRootRoot;
		float originY; //the point from which the tree originates
		float originX; //the point from which the tree originates

		boolean shouldSave = false;
		float dY;
		float dX;
		private float dYSinceReadjust = 0; //the amount we have scrolled in the DY since the last exchange of root and previousroot
		private float dXSinceReadjust = 0; //the amount we have scrolled in the DX since the last exchange of root and previousroot
		private double coefficientDX = 0.0;//coefficient of canvas movement
		private double coefficientDY = 1.0; //coefficient of canvas movement 1.0 to start in order to translate vertically
		double angleToRotate;
		private static final int SPEED = 10; //canvas scroll speed in pixels per second
		private float branchLength;
		

		private boolean dRight;
		private boolean dLeft;
		private boolean dUp;
		private boolean dDown;

		private int mCanvasWidth;
		private int mCanvasHeight;

		private long mLastTime;
		/** Message handler used by thread to post stuff back to the GameView */
		private Handler mHandler;

		/** The state of the game. One of READY, RUNNING, PAUSE, LOSE, or WIN */
		private GameState mMode;
		/** Indicate whether the surface has been created & is ready to draw */
		private boolean mRun = false;
		/** Handle to the surface manager object we interact with */
		private SurfaceHolder mSurfaceHolder;

		public GameThread(SurfaceHolder surfaceHolder, Context context,
				Handler handler) {
			// get handles to some important objects
			mSurfaceHolder = surfaceHolder;
			mHandler = handler;
			mContext = context;

			mSnowflake = BitmapFactory.decodeResource(mContext.getResources(), R.drawable.snowflake);

		}

		/**
		 * Starts the game, setting parameters for the current difficulty.
		 */
		public void doStart() {
			synchronized (mSurfaceHolder) {
				// Initialize game here!
				originX = mCanvasWidth/2;
				originY = mCanvasHeight -50;
				root = new TreeNode(null,new Point (originX,originY-30));
				//root = new TreeNode(null,new Point ((mCanvasWidth/2) ,mCanvasHeight*3/4));
				previousRoot = new TreeNode(new TreeNode[]{root},new Point(originX ,originY));
			//	previousRootRootLocation = previousRoot.getLocation();
			//	previousRootRoot = previousRoot;
				branchLength = mCanvasHeight /20;
				root.branch(3, branchLength, previousRoot.getLocation());
				for(TreeNode tn : root.getChildren())
				{
					tn.branch(3, branchLength, root.getLocation());
					/*//MORE RECURSION
					
					for(TreeNode tnc :tn.getChildren())
					{
						tnc.branch(3, branchLength, tn.getLocation());
						for(TreeNode tncc:tnc.getChildren())
							tncc.branch(3, branchLength, tnc.getLocation());
					}*/
				
				}
				mLastTime = System.currentTimeMillis() + 100;
				setState(GameState.RUNNING);
			}
		}

		private Bitmap mSnowflake;

		/**
		 * Pauses the physics update & animation.
		 */
		public void pause() {
			synchronized (mSurfaceHolder) {
				if (mMode == GameState.RUNNING) 
					setState(GameState.PAUSE);
			}
		}

		@Override
		public void run() {
			while (mRun) {
				Canvas c = null;
				try {
					c = mSurfaceHolder.lockCanvas(null);
					synchronized (mSurfaceHolder) {
						if (mMode == GameState.RUNNING) 
							updateGame();
						doDraw(c);
					}
				} finally {
					// do this in a finally so that if an exception is thrown
					// during the above, we don't leave the Surface in an
					// inconsistent state
					if (c != null) {
						mSurfaceHolder.unlockCanvasAndPost(c);
					}
				}
			}
		}

		/**
		 * Used to signal the thread whether it should be running or not.
		 * Passing true allows the thread to run; passing false will shut it
		 * down if it's already running. Calling start() after this was most
		 * recently called with false will result in an immediate shutdown.
		 * 
		 * @param b true to run, false to shut down
		 */
		public void setRunning(boolean b) {
			mRun = b;
		}

		/**
		 * Sets the game mode. That is, whether we are running, paused, in the
		 * failure state, in the victory state, etc.
		 * 
		 * @see #setState(int, CharSequence)
		 * @param mode one of the STATE_* constants
		 */
		public void setState(GameState mode) {
			synchronized (mSurfaceHolder) {
				setState(mode, null);
			}
		}

		/**
		 * Sets the game mode. That is, whether we are running, paused, in the
		 * failure state, in the victory state, etc.
		 * 
		 * @param mode one of the STATE_* constants
		 * @param message string to add to screen or null
		 */
		public void setState(GameState mode, CharSequence message) {
			synchronized (mSurfaceHolder) {
				mMode = mode;
			}
		}

		/* Callback invoked when the surface dimensions change. */
		public void setSurfaceSize(int width, int height) {
			// synchronized to make sure these all change atomically
			synchronized (mSurfaceHolder) {
				mCanvasWidth = width;
				mCanvasHeight = height;
			}
		}

		/**
		 * Resumes from a pause.
		 */
		public void unpause() {
			// Move the real time clock up to now
			synchronized (mSurfaceHolder) {
				mLastTime = System.currentTimeMillis() + 100;
			}
			setState(GameState.RUNNING);
		}

		/**
		 * Handles a key-down event.
		 * 
		 * @param keyCode the key that was pressed
		 * @param msg the lastPointal event object
		 * @return true
		 */
		boolean doKeyDown(int keyCode, KeyEvent msg) {
			boolean handled = false;
			synchronized (mSurfaceHolder) {
				if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT){
					dRight = true;
					handled = true;
				}
				if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT){
					dLeft = true;
					handled = true;
				}
				if (keyCode == KeyEvent.KEYCODE_DPAD_UP){
					dUp = true;
					handled = true;
				}
				if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN){
					dDown = true;
					handled = true;
				}
				return handled;
			}
		}

		/**
		 * Handles a key-up event.
		 * 
		 * @param keyCode the key that was pressed
		 * @param msg the lastPointal event object
		 * @return true if the key was handled and consumed, or else false
		 */
		boolean doKeyUp(int keyCode, KeyEvent msg) {
			boolean handled = false;
			synchronized (mSurfaceHolder) {
				if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT){
					dRight = false;
					handled = true;
				}
				if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT){
					dLeft = false;
					handled = true;
				}
				if (keyCode == KeyEvent.KEYCODE_DPAD_UP){
					dUp = false;
					handled = true;
				}
				if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN){
					dDown = false;
					handled = true;
				}
				return handled;
			}
		}


		private void doDraw(Canvas canvas) {
			
			if (root != null) //only draw tree if not null
			{
				Paint pm =new Paint();
				pm.setColor(Color.WHITE);
				canvas.drawText("helloworld", 10, 10,pm );
			}
		}
		public void drawTree(Canvas canvas,TreeNode current, Paint pm)
		{
			pm.setColor(Color.WHITE);
			if(current.getLocation() == null)
				return;
			//draw from the last node to this node (in the case of root, draw offscreen to this node)
			if(current.getChildren() == null)
				return;
			int i = 0;
			for(TreeNode child : current.getChildren()) //draw from this node to every child node, then do the same for them (recurse)
			{
				if (child == null || child.getLocation() == null)
					continue;
				if(i==0)
					pm.setColor(Color.MAGENTA);
				else if ( i==1)
					pm.setColor(Color.YELLOW);
				else if (i==2)
					pm.setColor(Color.GREEN);

				//draw from this node to the child
				canvas.drawLine(current.getLocation().getX(), current.getLocation().getY() , child.getLocation().getX(), child.getLocation().getY(), pm); 
				drawTree(canvas,child,pm); //draw this child and its children!
				i++;
			}
		}

		/**
		 * Updates the game.
		 */
		private void updateGame() {
			//// <DoNotRemove>
			long now = System.currentTimeMillis();
			// Do nothing if mLastTime is in the future.
			// This allows the game-start to delay the start of the physics
			// by 100ms or whatever.
			if (mLastTime > now) 
				return;
			double elapsed = (now - mLastTime) / 1000.0;
			mLastTime = now;
			//// </DoNotRemove>

			/*
			 * Why use mLastTime, now and elapsed?
			 * Well, because the frame rate isn't always constant, it could happen your normal frame rate is 25fps
			 * then your char will walk at a steady pace, but when your frame rate drops to say 12fps, without elapsed
			 * your character will only walk half as fast as at the 25fps frame rate. Elapsed lets you manage the slowdowns
			 * and speedups!
			 */
			float thisdY = (float)(elapsed*SPEED*coefficientDX); //the total change in dy this timestep
			float thisdX = (float)(elapsed*SPEED*coefficientDY); //the total change in dX this timestep
			dY =thisdY; //dY is the total dY, over time (since the begining of the applicaiton's lifecycle
			dX =thisdX;
			dYSinceReadjust += thisdY;
			dXSinceReadjust += thisdX;
			//we are near the end node
			//TODO: THIS NEEDS TO BE FIXED.
			if(Math.sqrt(Math.pow(dXSinceReadjust,2) +Math.pow(dYSinceReadjust,2)) >= branchLength)
			{
				
				previousRootRootLocation = previousRoot.getLocation(); // we need the previous root's location for drawing the whole tree
				previousRoot = root; // keep track of our last point for drawing
				root = root.getChildren()[2]; // branch on the tree, This is hacked, just choosing the right node

				for(TreeNode child : root.getChildren())
				{
					child.branch(3, branchLength, root.getLocation()); //NEW, JULIAN
					//child.branch(3, branchLength, Point.translate(root.getLocation(),0, dYSinceReadjust)); //OLD, CORY
					/*//MORE RECURSION
					for(TreeNode baby: child.getChildren())
					{
						baby.branch(3, branchLength, child.getLocation());
						for(TreeNode zygote: baby.getChildren())
						{
							zygote.branch(3, branchLength, child.getLocation());
						}
					}*/
				}
				
				
				//reset our accumulators
				/*
				float nextDispX = previousRoot.getLocation().getX()-root.getLocation().getX();
				float nextDispY = previousRoot.getLocation().getY()-root.getLocation().getY();
				float magnitude = previousRoot.getLocation().distanceTo(root.getLocation()); 
				*/
				angleToRotate += 30; //right turn, debug only
				coefficientDX=Math.sin(-30*Math.PI/180); //coefficient of canvas velocity
				coefficientDY= Math.cos(-30*Math.PI/180); //coefficient of canvas velocity

				dYSinceReadjust = 0;
				dXSinceReadjust = 0;
				shouldSave = true;
			}

		}
	}

	/** Handle to the application context, used to e.g. fetch Drawables. */
	private Context mContext;

	/** The thread that actually draws the animation */
	private GameThread thread;

	public GameView(Context context, AttributeSet attrs) {
		super(context, attrs);

		// register our interest in hearing about changes to our surface
		SurfaceHolder holder = getHolder();
		holder.addCallback(this);

		// create thread only; it's started in surfaceCreated()
		thread = new GameThread(holder, context, new Handler() {
			@Override
			public void handleMessage(Message m) {
				// Use for pushing back messages.
			}
		});

		setFocusable(true); // make sure we get key events
	}

	/**
	 * Fetches the animation thread corresponding to this LunarView.
	 * 
	 * @return the animation thread
	 */
	public GameThread getThread() {
		return thread;
	}

	/**
	 * Standard override to get key-press events.
	 */
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent msg) {
		return thread.doKeyDown(keyCode, msg);
	}

	/**
	 * Standard override for key-up. We actually care about these, so we can
	 * turn off the engine or stop rotating.
	 */
	@Override
	public boolean onKeyUp(int keyCode, KeyEvent msg) {
		return thread.doKeyUp(keyCode, msg);
	}

	/**
	 * Standard window-focus override. Notice focus lost so we can pause on
	 * focus lost. e.g. user switches to take a call.
	 */
	@Override
	public void onWindowFocusChanged(boolean hasWindowFocus) {
		if (!hasWindowFocus)
			thread.pause();
	}

	/* Callback invoked when the surface dimensions change. */
	public void surfaceChanged(SurfaceHolder holder, int format, int width,
			int height) {
		thread.setSurfaceSize(width, height);
	}

	/*
	 * Callback invoked when the Surface has been created and is ready to be
	 * used.
	 */
	public void surfaceCreated(SurfaceHolder holder) {
		// start the thread here so that we don't busy-wait in run()
		// waiting for the surface to be created
		thread.setRunning(true);
		thread.start();
	}

	/*
	 * Callback invoked when the Surface has been destroyed and must no longer
	 * be touched. WARNING: after this method returns, the Surface/Canvas must
	 * never be touched again!
	 */
	public void surfaceDestroyed(SurfaceHolder holder) {
		// we have to tell thread to shut down & wait for it to finish, or else
		// it might touch the Surface after we return and explode
		boolean retry = true;
		thread.setRunning(false);
		while (retry) {
			try {
				thread.join();
				retry = false;
			} catch (InterruptedException e) {
			}
		}
	}
	
	class SwipeDetector extends SimpleOnGestureListener {
        
	    // returns true if we use the event
	    // otherwise returns false
	           
	   public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {              
	        int scaledDistance;
	        int scaledPath;
	                
	        // get distance between points of the fling
	        double vertical = Math.abs( e1.getY() - e2.getY() );
	        double horizontal = Math.abs( e1.getX() - e2.getX() );
	                
	        // convert dip measurements to pixels
	        final float scale = getResources().getDisplayMetrics().density;
	        scaledDistance = (int) ( DISTANCE_DIP * scale + 0.5f );
	        scaledPath = (int) ( PATH_DIP * scale + 0.5f );         
	                
	        // test vertical distance, make sure it's a swipe
	        if ( vertical > PATH_DIP ) {
	                return false;
	        }
	        // test horizontal distance and velocity
	        else if ( horizontal > DISTANCE_DIP && Math.abs(velocityX) > minScaledVelocity ) {
	        // right to left swipe
	        if (velocityX < 0 ) {
	        	Log.i("SWIPE", "LSWIPE");
	        }
	        // left to right swipe
	        else {
	        	Log.i("SWIPE", "RSWIPE");
	        }
	            return true;
	        }
	            return false;

	   }
	}
}
