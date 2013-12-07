package serverRMI;

import common.IdeaInfo;
import common.IdeasNestedPack;
import common.rmi.*;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.*;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;

import org.json.JSONException;
import org.json.JSONObject;
import org.scribe.builder.ServiceBuilder;
import org.scribe.builder.api.FacebookApi;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.oauth.OAuthService;

/**
 * Created with IntelliJ IDEA.
 * User: joaonuno
 * Date: 10/13/13
 * Time: 6:36 PM
 * To change this template use File | Settings | File Templates.
 */
public class Ideas extends UnicastRemoteObject implements RemoteIdeas
{
    public Ideas() throws RemoteException{
        super();
    }

    /**
     * Insert new idea in database.
     * @param topics Topic text. If topic not exists, a new topic is created.
     * @param user_id User who posted idea.
     * @param investment
     * @param text
     * @throws RemoteException
     * @throws SQLException
     */
    public void submitIdea(ArrayList<String> topics, int user_id, double investment, String text, byte[] fileData, String filename, int current, String token, String username, String faceId) throws RemoteException, SQLException, IOException
    {
        if (!filename.equals("-")) {
            FileOutputStream fos = new FileOutputStream("assets/"+filename);
            BufferedOutputStream bos = new BufferedOutputStream(fos);

            bos.write(fileData, 0 , current);
            bos.flush();
            bos.close();
        }

        Connection db = ServerRMI.getConnection();

        int tries = 0;
        int maxTries = 3;
        PreparedStatement stmt = null;
        ArrayList<Integer> topicIds = new ArrayList<Integer>();
        ResultSet rs;

        try {
		    db.setAutoCommit(false);

		    String query = "SELECT * FROM idea WHERE user_id = ? AND number_parts = ? AND active = ? AND text LIKE ? AND attach LIKE ? AND in_hall LIKE ?";

		    try {
			    stmt = db.prepareStatement(query);
			    stmt.setInt(1, user_id);
			    stmt.setInt(2, 100000);
			    stmt.setInt(3, 1);
			    stmt.setString(4, text);
			    stmt.setString(5, filename);
                stmt.setInt(6, 0);

			    rs = stmt.executeQuery();

			    if(rs.next())
				    return;
		    } catch (SQLException e) {
			    System.out.println(e);
			    if(db != null) {
				    db.rollback();
			    }
		    } finally {
			    if(stmt != null) {
				    stmt.close();
			    }
		    }

		    //Get topic ID.
		    String aux;
            int topic_id;
            System.out.println("Size 2: "+topics.size());
		    for(int i=0; i < topics.size(); i++)
		    {
			    aux = topics.get(i);
			    try {
                    topic_id = ServerRMI.topics.getTopicID(aux);
                    if (topic_id != -1) {
                        topicIds.add(topic_id);
                    }
                    else {
                        topic_id = ServerRMI.topics.newTopic(aux);
                        topicIds.add(topic_id);
                    }
			    } catch (ExistingTopicException ete) {
                    db.close();
				    // Topic already exists
			    }
		    }

            System.out.println("Post in facebook");

            //Post on facebook
            OAuthService service = new ServiceBuilder()
                    .provider(FacebookApi.class)
                    .apiKey("436480809808619")
                    .apiSecret("af8edf703b7a95f5966e9037b545b7ce")
                    .callback("http://localhost:8080")   //should be the full URL to this action
                    .build();

            OAuthRequest authRequest = new OAuthRequest(Verb.POST, "https://graph.facebook.com/me/feed");
            authRequest.addHeader("Content-Type", "text/html");
            authRequest.addBodyParameter("message", username
                    + " criou a seguinte ideia: \"" + text + "\"\nInvestiu " +
                    investment + " DEICoins!");
            Token token_final = new Token(token,"af8edf703b7a95f5966e9037b545b7ce");

            service.signRequest(token_final, authRequest);
            Response authResponse = authRequest.send();

            String messageId = null;

            try {
                messageId = new JSONObject(authResponse.getBody()).getString("id");
            } catch (JSONException e) {
                e.printStackTrace();
                //FIXME WHAT TO DO WITH THIS?????
            }

		    //Insert idea.
		    query = "INSERT INTO idea (id,user_id,number_parts,active,text,attach,in_hall,face_id) VALUES (seq_idea.nextval,?,?,?,?,?,?,?)";

            try {
                stmt = db.prepareStatement(query);
                stmt.setInt(1, user_id);
                stmt.setInt(2, 100000);
                stmt.setInt(3, 1);
                stmt.setString(4, text);
                stmt.setString(5, filename);
                stmt.setInt(6, 0);
                stmt.setString(7, messageId);

                stmt.executeQuery();
            } catch (SQLException e) {
                System.out.println(e);
                if(db != null) {
                    db.rollback();
                }
            } finally {
                if(stmt != null) {
                    stmt.close();
                }
            }

            int idea_id = 0;

            query = "SELECT seq_idea.currval as id FROM dual";

            try {
                stmt = db.prepareStatement(query);

                rs = stmt.executeQuery();

                rs.next();

                idea_id = rs.getInt("id");
            } catch (SQLException e) {
                System.out.println(e);
                if(db != null) {
                    db.rollback();
                }
            } finally {
                if(stmt != null) {
                    stmt.close();
                }
            }


		    //Create relationship between topic and idea.
		    query = "INSERT INTO idea_has_topic (idea_id,topic_id) VALUES (?,?)";

		    for(int i=0; i < topicIds.size(); i++)
		    {

                try {
                    stmt = db.prepareStatement(query);
                    stmt.setInt(1, idea_id);
                    stmt.setInt(2, topicIds.get(i));

                    stmt.executeQuery();
                } catch (SQLException e) {
                    if(db != null) {
                        db.rollback();
                    }
                } finally {
                    if(stmt != null) {
                        stmt.close();
                    }
                }

		    }

            //Insert initial share of idea
            query = "INSERT INTO idea_share (id,idea_id,user_id,parts,VALUE) VALUES (seq_idea_share.nextval,?,?,?,?)";

            try {
                stmt = db.prepareStatement(query);
                stmt.setInt(1, idea_id);
                stmt.setInt(2, user_id);
                stmt.setInt(3, 100000);
                stmt.setDouble(4, investment / 100000);

                stmt.executeQuery();
            } catch (SQLException e) {
                System.out.println(e);
                if(db != null) {
                    db.rollback();
                }
            } finally {
                if(stmt != null) {
                    stmt.close();
                }
            }

            giveOrTakeUserCash(db,user_id,investment,false);

		    db.commit();
	    } catch (SQLException e) {
		    System.out.println("\n"+e+"\n");
		    if(db != null)
			    db.rollback();
		    throw e;
	    } finally {
		    db.setAutoCommit(true);
		    db.close();
	    }
	}

    protected void giveOrTakeUserCash(Connection db, int user_id, double money, boolean give) throws SQLException
    {
        //Get current cash.
        int curCash = 0;
        int tries = 0, maxTries = 3;
        PreparedStatement gotCash = null;
        ResultSet rs;

        String cash = "SELECT cash FROM sduser WHERE id = ?";

        while(tries < maxTries)
        {
            try {
                gotCash = db.prepareStatement(cash);
                gotCash.setInt(1, user_id);

                rs = gotCash.executeQuery();
                if(rs.next())
                {
                    curCash = rs.getInt("cash");
                    break;
                }
            } catch (SQLException e) {
                System.out.println(e);
                if(tries++ > maxTries)
                    throw e;
            } finally {
                if(gotCash != null)
                    gotCash.close();
            }
        }

        //Update cash.
        if(give)
            updateUserCash(db, user_id, curCash + money);
        else
            updateUserCash(db, user_id, curCash - money);
    }

    protected void updateUserCash(Connection db, int user_id, double cash) throws SQLException
    {
        int tries = 0;
        int maxTries = 3;
        PreparedStatement uShare = null;

        String share = "UPDATE sduser SET cash = ? WHERE id = ?";

        while(tries < maxTries)
        {
            try {
                uShare = db.prepareStatement(share);
                uShare.setDouble(1, cash);
                uShare.setInt(2, user_id);

                uShare.executeQuery();
                break;
            } catch (SQLException e) {
                System.out.println(e);
                if(tries++ > maxTries)
                    throw e;
            }
            finally {
                if(uShare != null)
                    uShare.close();
            }
        }
    }

	/**
	 * Delete idea identified by <em>idea_id</em>.
	 * @param idea_id The identifier of the idea to delete.
	 * @param user_id The identifier of the user trying to delete the idea.
	 * @throws RemoteException
	 * @throws SQLException
	 * @throws NotFullOwnerException
	 */
    public void deleteIdea(int idea_id, int user_id, String token) throws RemoteException, SQLException, NotFullOwnerException
    {
	    Connection db = ServerRMI.getConnection();

	    try {
		    //Verify that user owns all shares.
		    int numParts = ServerRMI.transactions.getNumberShares(db, idea_id);

            System.out.println("Idea has "+numParts+" shares.");

		    int tries = 0;
		    int maxTries = 3;
		    PreparedStatement stmt = null;
		    ResultSet rs;

		    String verify = "SELECT parts FROM idea_share WHERE idea_id = ? AND user_id = ?";

		    while(tries < maxTries)
		    {
			    try {
				    stmt = db.prepareStatement(verify);
				    stmt.setInt(1, idea_id);
				    stmt.setInt(2, user_id);

				    rs = stmt.executeQuery();

				    if(rs.next())
				    {
                        System.out.println("Entrei");
					    if(rs.getInt("parts") == numParts)
						    break;
                        else {
                            System.out.println("User can't remove.");
                            throw new NotFullOwnerException();
                        }
				    }
                    return;
			    } catch (SQLException e) {
                    System.out.println(e);
				    if(tries++ > maxTries) {
					    throw e;
				    }
			    } finally {
				    if(stmt != null)
					    stmt.close();
			    }
		    }

		    //Update active field to 0.
		    String update = "UPDATE idea SET active = 0 WHERE id = ?";

		    while(tries < maxTries)
		    {
			    try {
				    stmt = db.prepareStatement(update);
				    stmt.setInt(1, idea_id);

				    stmt.executeQuery();
				    break;
			    } catch (SQLException e) {
				    System.out.println(e);
				    if(tries++ > maxTries)
					    throw e;
			    } finally {
				    if(stmt != null)
					    stmt.close();
			    }
		    }

            System.out.println("Updated");

		    //Remove from idea_has_topic.
		    String remove = "DELETE FROM idea_has_topic WHERE idea_id = ?";
		    while(tries < maxTries)
		    {
			    try {
				    stmt = db.prepareStatement(remove);
				    stmt.setInt(1, idea_id);

				    stmt.executeQuery();
				    break;
			    } catch (SQLException e) {
				    System.out.println(e);
				    if(tries++ > maxTries)
					    throw e;
			    } finally {
				    if(stmt != null)
					    stmt.close();
			    }
		    }

            System.out.println("Deleted from idea_has_topic");

		    //Remove from shares.
		    remove = "DELETE FROM idea_share WHERE idea_id = ?";
		    while(tries < maxTries)
		    {
			    try {
				    stmt = db.prepareStatement(remove);
				    stmt.setInt(1, idea_id);

				    stmt.executeQuery();
				    break;
			    } catch (SQLException e) {
				    System.out.println(e);
				    if(tries++ > maxTries)
					    throw e;
			    } finally {
				    if(stmt != null)
					    stmt.close();
			    }
		    }

            System.out.println("Shares deleted");

		    //Remove from Watchlist.
		    removeFromWatchlist(db, idea_id);

            System.out.println("Removed from watch");

		    //Remove from Hall of Fame.
		    removeFromHallOfFame(db, idea_id);

            System.out.println("Removed From hall of fame");

		    //Remove from Transaction Queue.
		    TransactionalTrading.removeAllByIdea(db, idea_id);

            System.out.println("Removed from queue");

            deleteIdeaFromFb(db,idea_id,token);

            System.out.println("Deleted from Face");

		    db.commit();
	    } catch (SQLException e) {
		    System.out.println(e);
		    if(db != null)
			    db.rollback();
	    } finally {
		    db.setAutoCommit(true);
	    }
	}

    protected void deleteIdeaFromFb(Connection db, int idea_id, String token) throws SQLException {
        String ideaFBid = getFbIdeaID(db, idea_id);

        //Post on facebook
        OAuthService service = new ServiceBuilder()
                .provider(FacebookApi.class)
                .apiKey("436480809808619")
                .apiSecret("af8edf703b7a95f5966e9037b545b7ce")
                .callback("http://localhost:8080")   //should be the full URL to this action
                .build();

        OAuthRequest authRequest = new OAuthRequest(Verb.DELETE, "https://graph.facebook.com/" + ideaFBid);
        Token token_final = new Token(token,"af8edf703b7a95f5966e9037b545b7ce");

        service.signRequest(token_final, authRequest);
        Response authResponse = authRequest.send();
    }

    /**
     * Get the facebook id of the post made when the idea was created.
     * @param db The connection to the database.
     * @param idea_id The identifier of the idea.
     * @return The facebook id of the idea.
     */
    protected String getFbIdeaID(Connection db, int idea_id) throws SQLException
    {
        int tries = 0;
        int maxTries = 3;
        PreparedStatement gFbId = null;
        ResultSet rs;

        String query = "SELECT idea.face_id FROM idea WHERE idea.id = ?";

        while(tries < maxTries)
        {
            try {
                gFbId = db.prepareStatement(query);
                gFbId.setInt(1, idea_id);

                rs = gFbId.executeQuery();

                if(rs.next())
                    return rs.getString("face_id");
            } catch (SQLException e) {
                System.out.println(e);
                if(tries++ > maxTries)
                    throw e;
            } finally {
                if(gFbId != null)
                    gFbId.close();
            }
        }

        return "";
    }

	/**
	 * Remove idea from the watchlists of all users.
	 * @param db The connection to the database.
	 * @param idea_id The identifier of the idea.
	 * @throws SQLException
	 */
	protected void removeFromWatchlist(Connection db, int idea_id) throws SQLException
	{
		PreparedStatement rWatch = null;
		String query = "DELETE FROM watchlist WHERE idea_id = ?";

		boolean success = false;
		while(!success)
		{
			try {

				try {
					rWatch = db.prepareStatement(query);
					rWatch.setInt(1, idea_id);

					rWatch.executeQuery();
					db.commit();

					success = true;
				} catch (SQLException e) {
                    System.out.println(e);
					success = false;
				} finally {
					if(rWatch != null)
						rWatch.close();
				}
			} catch (SQLException e) {
                System.out.println(e);
				success = false;
			}
		}
	}

	/**
	 * Remove an idea from the Hall of Fame
	 * @param db The connection to the database.
	 * @param idea_id The identifier of the idea.
	 * @throws SQLException
	 */
	protected void removeFromHallOfFame(Connection db, int idea_id) throws SQLException
	{
		PreparedStatement rHall = null;
		String query = "UPDATE idea SET in_hall = 0 WHERE id = ?";

		boolean success = false;
		while(!success)
		{
			try {

				try {
					rHall = db.prepareStatement(query);
					rHall.setInt(1, idea_id);

					rHall.executeQuery();
					db.commit();

					success = true;
				} catch (SQLException e) {
					success = false;
				} finally {
					if(rHall != null)
						rHall.close();
				}
			} catch (SQLException e) {
				success = false;
			}
		}
	}

	/**
	 *
	 * @param topic_id
	 * @return
	 * @throws RemoteException
	 * @throws SQLException
	 */
    public ArrayList<IdeaInfo> viewIdeasTopic(int topic_id, int user_id) throws RemoteException, SQLException
    {
        Connection db = ServerRMI.getConnection();

        db.setAutoCommit(false);

        int tries = 0;
        int maxTries = 3;
        PreparedStatement stmt = null;
        ResultSet rs;
        ArrayList<IdeaInfo> ideas = new ArrayList<IdeaInfo>();

        String query = "SELECT lol.id, lol.text, lol.username, lol.sduser_id, lol.idea_id FROM (SELECT idea.id, idea.text, sduser.username, lol.sduser_id, lol.idea_id, idea.in_hall FROM idea LEFT OUTER JOIN (Select watchlist.* FROM watchlist where sduser_id = ?) lol ON idea.id = lol.idea_id LEFT OUTER JOIN sduser ON sduser.id = idea.user_id) lol, idea_has_topic WHERE lol.id = idea_has_topic.idea_id AND idea_has_topic.topic_id = ? AND lol.in_hall = 0";

        while(tries < maxTries)
        {
            try {
                stmt = db.prepareStatement(query);
                stmt.setInt(1, user_id);
                stmt.setInt(2, topic_id);

                rs = stmt.executeQuery();

                while(rs.next()) {
                    if(rs.getString("sduser_id") == null) {
                        ideas.add(new IdeaInfo(rs.getInt("id"), rs.getString("username"), rs.getString("text"), 0));
                    }
                    else {
                        ideas.add(new IdeaInfo(rs.getInt("id"), rs.getString("username"), rs.getString("text"), 1));
                    }

                }
                break;
            } catch (SQLException e) {
                System.out.println(e);
                if(tries++ > maxTries) {
                    throw e;
                }
            } finally {
	            if(stmt != null)
		            stmt.close();
            }
        }

        return ideas;
    }

    public void addToWatchlist(int user_id, int idea_id) throws SQLException
    {
        Connection db = ServerRMI.getConnection();

        int tries = 0;
        int maxTries = 3;
        PreparedStatement stmt = null;

        System.out.println(user_id+" "+idea_id);

        String query = "INSERT INTO watchlist (idea_id,sduser_id) values (?,?)";

        while(tries < maxTries)
        {
            try {
                stmt = db.prepareStatement(query);
                stmt.setInt(1, idea_id);
                stmt.setInt(2, user_id);

                stmt.executeQuery();
                break;
            } catch (SQLException e) {
                System.out.println(e);
                if(tries++ > maxTries) {
                    throw e;
                }
            } finally {
                if(stmt != null)
                    stmt.close();
            }
        }
    }

    public ArrayList<IdeaInfo> viewWatchlist(int user_id) throws SQLException, RemoteException {

        Connection db = ServerRMI.getConnection();

        ArrayList<IdeaInfo> ideas = new ArrayList<IdeaInfo>();

        try {
            int tries = 0;
            int maxTries = 3;
            PreparedStatement stmt = null;
            ResultSet rs;

            db.setAutoCommit(false);

            String verify = "SELECT idea.id, idea.text, sduser.username FROM watchlist, idea, sduser WHERE watchlist.sduser_id = ? AND idea.id = watchlist.idea_id AND idea.user_id = sduser.id AND idea.in_hall = 0";

            while(tries < maxTries)
            {
                try {
                    stmt = db.prepareStatement(verify);
                    stmt.setInt(1, user_id);

                    rs = stmt.executeQuery();

                    while(rs.next())
                    {
                         ideas.add(new IdeaInfo(rs.getInt("id"), rs.getString("username"), rs.getString("text"), 1));
                    }
                    break;
                } catch (SQLException e) {
                    System.out.println(e);
                    if(tries++ > maxTries) {
                        throw e;
                    }
                } finally {
                    if(stmt != null)
                        stmt.close();
                }
            }
            db.commit();
        } catch (SQLException e) {
            System.out.println(e);
            if(db != null)
                db.rollback();
        } finally {
            db.setAutoCommit(true);
        }
        return ideas;
    }

    public ArrayList<IdeaInfo> viewHallOfFame() throws RemoteException, SQLException {
        Connection db = ServerRMI.getConnection();

        ArrayList<IdeaInfo> ideas = new ArrayList<IdeaInfo>();

        try {
            int tries = 0;
            int maxTries = 3;
            PreparedStatement stmt = null;
            ResultSet rs;

            db.setAutoCommit(false);

            String verify = "SELECT idea.id, idea.text, sduser.username FROM idea, sduser WHERE idea.in_hall = 1 AND idea.user_id = sduser.id";

            while(tries < maxTries)
            {
                try {
                    stmt = db.prepareStatement(verify);

                    rs = stmt.executeQuery();

                    while(rs.next())
                    {
                        ideas.add(new IdeaInfo(rs.getInt("id"), rs.getString("username"), rs.getString("text"), 1));
                    }
                    break;
                } catch (SQLException e) {
                    System.out.println(e);
                    if(tries++ > maxTries) {
                        throw e;
                    }
                } finally {
                    if(stmt != null)
                        stmt.close();
                }
            }
            db.commit();
        } catch (SQLException e) {
            System.out.println(e);
            if(db != null)
                db.rollback();
        } finally {
            db.setAutoCommit(true);
        }
        return ideas;
    }

    public ArrayList<IdeaInfo> viewPortfolio(int user_id) throws SQLException, RemoteException {
        Connection db = ServerRMI.getConnection();

        ArrayList<IdeaInfo> ideas = new ArrayList<IdeaInfo>();

        try {
            int tries = 0;
            int maxTries = 3;
            PreparedStatement stmt = null;
            ResultSet rs;

            db.setAutoCommit(false);

            String verify = "SELECT idea.id, idea.text, sduser.username, idea_share.parts, idea_share.value FROM idea, idea_share, sduser WHERE idea.user_id = sduser.id AND idea_share.user_id = ? AND idea_share.idea_id = idea.id";

            while(tries < maxTries)
            {
                try {
                    stmt = db.prepareStatement(verify);

                    stmt.setInt(1, user_id);

                    rs = stmt.executeQuery();

                    while(rs.next())
                    {
                        ideas.add(new IdeaInfo(rs.getInt("id"), rs.getString("username"), rs.getString("text"), 1, rs.getInt("parts"), rs.getDouble("value")));
                    }
                    break;
                } catch (SQLException e) {
                    System.out.println(e);
                    if(tries++ > maxTries) {
                        throw e;
                    }
                } finally {
                    if(stmt != null)
                        stmt.close();
                }
            }
            db.commit();
        } catch (SQLException e) {
            System.out.println(e);
            if(db != null)
                db.rollback();
        } finally {
            db.setAutoCommit(true);
        }
        return ideas;
    }

    public ArrayList<IdeaInfo> searchIdea(String ideaKey, int user_id) throws SQLException, RemoteException {
        Connection db = ServerRMI.getConnection();

        ArrayList<IdeaInfo> ideas = new ArrayList<IdeaInfo>();

        try {
            int tries = 0;
            int maxTries = 3;
            PreparedStatement stmt = null;
            ResultSet rs;

            db.setAutoCommit(false);

            String key = "%"+ideaKey+"%";

            String verify = "SELECT lol.id, lol.text, lol.username, lol.sduser_id, lol.idea_id FROM (SELECT idea.id, idea.text, sduser.username, lol.sduser_id, lol.idea_id, idea.in_hall FROM idea LEFT OUTER JOIN (Select watchlist.* FROM watchlist where sduser_id = ?) lol ON idea.id = lol.idea_id LEFT OUTER JOIN sduser ON sduser.id = idea.user_id) lol WHERE lol.in_hall = 0 AND lol.text LIKE ?";

            while(tries < maxTries)
            {
                try {
                    stmt = db.prepareStatement(verify);
                    stmt.setInt(1, user_id);
                    stmt.setString(2, key);

                    rs = stmt.executeQuery();

                    while(rs.next())
                    {
                        if(rs.getString("sduser_id") == null) {
                            ideas.add(new IdeaInfo(rs.getInt("id"), rs.getString("username"), rs.getString("text"), 0));
                        }
                        else {
                            ideas.add(new IdeaInfo(rs.getInt("id"), rs.getString("username"), rs.getString("text"), 1));
                        }
                    }
                    break;
                } catch (SQLException e) {
                    System.out.println(e);
                    if(tries++ > maxTries) {
                        throw e;
                    }
                } finally {
                    if(stmt != null)
                        stmt.close();
                }
            }
            db.commit();
        } catch (SQLException e) {
            System.out.println(e);
            if(db != null)
                db.rollback();
        } finally {
            db.setAutoCommit(true);
        }
        return ideas;
    }
}
