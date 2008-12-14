package hu.belicza.andras.hackerdb;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.dbcp.BasicDataSource;

import static hu.belicza.andras.hackerdb.ApiConsts.*;

/**
 * Servlet to serve hacker list related requests.<br>
 * These include:
 * <ul>
 * 	<li>Sending parts of the hacker list.
 * 	<li>Performing validation of authorization keys.
 * 	<li>Processing reports of hackers.
 * </ul>
 * 
 * @author Andras Belicza
 */
public class HackerDbServlet extends HttpServlet {
	
	/** URL of the hacker data base. */
	private static final String DATABASE_URL = "jdbc:hsqldb:hsql://localhost/hackers";
	
	/** Data source to provide pooled connections to the hacker database. */
	private static final BasicDataSource dataSource = new BasicDataSource();
	static {
		dataSource.setDriverClassName( "org.hsqldb.jdbcDriver" );
		dataSource.setUsername( "sa" );
		dataSource.setPassword( "" );
		dataSource.setUrl( DATABASE_URL );
	}
	
	
	@Override
	public void doGet( final HttpServletRequest request, final HttpServletResponse response ) {
		setNoCache( response );
		
		try {
			final String operation = request.getParameter( REQUEST_PARAMETER_NAME_OPERATION );
			
			if ( operation == null )
				throw new BadRequestException();
			
			if ( operation.equals( OPERATION_LIST ) ) {
				
			} else if ( operation.equals( OPERATION_CHECK ) ) {
				
				// Check the validity of an authorization key
				final String key = request.getParameter( REQUEST_PARAMETER_NAME_KEY );
				if ( key == null )
					throw new BadRequestException();
				
				sendBackPlainMessage( Boolean.toString( checkKey( key ) ), response );
				
			} else if ( operation.equals( OPERATION_REPORT ) ) {
				// Check the validity of an authorization key
				
				final String key = request.getParameter( REQUEST_PARAMETER_NAME_KEY );
				if ( key == null )
					throw new BadRequestException();
				
				int gatewayIndex = 0;
				try {
					gatewayIndex = Integer.parseInt( request.getParameter( REQUEST_PARAMETER_NAME_GATEWAY ) );
				}
				catch ( final Exception e ) {
					throw new BadRequestException();
				}
				if ( gatewayIndex < 0 || gatewayIndex >= ApiConsts.GATEWAYS.length )
					throw new BadRequestException();
				
				final String[] playerNames = new String[ 8 ];
				for ( int i = 0; i <= playerNames.length && ( playerNames[ i ] = request.getParameter( REQUEST_PARAMETER_NAME_PLAYER + i ) ) != null; i++ )
					;
				if ( playerNames[ 0 ] == null )
					throw new BadRequestException();
				
				sendBackPlainMessage( handleReport( key, gatewayIndex, playerNames, request.getRemoteAddr() ), response );
			}
		}
		catch ( final BadRequestException bre ) {
			sendBackErrorMessage( response );
		}
	}
	
	/**
	 * Checks a key in the data base if its valid.<br>
	 * A key is valid if it exists in the data base and it has not been revocated.
	 * @param key key to be checked
	 * @return true if the key is valid; false otherwise
	 */
	private boolean checkKey( final String key ) {
		Connection        connection = null;
		PreparedStatement statement  = null;
		ResultSet         resultSet  = null;
		
		try {
			connection = dataSource.getConnection();
			
			statement = connection.prepareStatement( "SELECT id FROM key WHERE revocated=FALSE AND value=?" );
			statement.setString( 1, key );
			
			resultSet = statement.executeQuery();
			if ( resultSet.next() )
				return true;
			
			return false;
		} catch ( final SQLException se ) {
			se.printStackTrace();
			return false;
		}
		finally {
			if ( resultSet != null )
				try { resultSet.close(); } catch ( final SQLException se ) {}
			if ( statement != null )
				try { statement.close(); } catch ( final SQLException se ) {}
			if ( connection != null )
				try { connection.close(); } catch ( final SQLException se ) {}
		}
	}
	
	/**
	 * Handles a report.
	 * @param key         authorization key of the reporter
	 * @param gateway     gateway of the reported players
	 * @param playerNames names of players being reported; only non-null values contain information
	 * @param ip          ip of the reporter's computer
	 * @return an error message if report fails; an empty string otherwise
	 */
	private String handleReport( final String key, final int gateway, final String[] playerNames, final String ip ) {
		Connection        connection = null;
		PreparedStatement statement  = null;
		ResultSet         resultSet  = null;
		PreparedStatement statement2 = null;
		Statement         statement3 = null;
		ResultSet         resultSet3 = null;
		
		try {
			connection = dataSource.getConnection();
			
			// First check the validity of the key
			statement = connection.prepareStatement( "SELECT id FROM key WHERE revocated=FALSE AND value=?" );
			statement.setString( 1, key );
			
			resultSet = statement.executeQuery();
			if ( !resultSet.next() )
				return "Invalid authorization key, report discarded!";
			
			final int keyId = resultSet.getInt( 1 );
			resultSet.close();
			statement.close();
			
			// The rest has to be a transaction
			connection.setAutoCommit( false );
			
			final Integer[] hackerIds = new Integer[ playerNames.length ];
			
			// Search existing hackers with the given names on the same gateways
			statement = connection.prepareStatement( "SELECT id FROM hacker WHERE gateway=? AND name=?" );
			for ( int i = 0; i < playerNames.length && playerNames[ i ] != null; i++ ) {
				statement.setInt   ( 1, gateway          );
				statement.setString( 2, playerNames[ i ] );
				
				resultSet = statement.executeQuery();
				if ( resultSet.next() ) {
					// We found the hacker in the data base
					hackerIds[ i ] = resultSet.getInt( 1 );
				}
				else {
					// New hacker, add it first
					statement2 = connection.prepareStatement( "INSERT INTO hacker (name,gateway) VALUES (?,?)" );
					statement2.setString( 1, playerNames[ i ] );
					statement2.setInt   ( 2, gateway          );
					if ( statement2.executeUpdate() > 0 ) {
						statement3 = connection.createStatement();
						resultSet3 = statement3.executeQuery( "CALL IDENTITY()" );
						if ( resultSet3.next() )
							hackerIds[ i ] = resultSet3.getInt( 1 );
						else
							throw new SQLException( "Could not get id of newly inserted hacker?" );
						resultSet3.close();
						statement3.close();
					}
					else
						throw new SQLException( "Could not insert new hacker?" );
					
					statement2.close();
				}
				
				resultSet.close();
			}
			statement.close();
			
			// Lastly insert the report records
			statement = connection.prepareStatement( "INSERT INTO report (hacker,key,ip) VALUES (?,?,?)" );
			statement.setInt   ( 2, keyId );
			statement.setString( 3, ip    );
			for ( int i = 0; i < hackerIds.length && hackerIds[ i ] != null; i++ ) {
				statement.setInt( 1, hackerIds[ i ] );
				if ( statement.executeUpdate() <= 0 )
					throw new SQLException( "Could not insert report?" );
			}
			statement.close();
			
			connection.commit();
			connection.setAutoCommit( true );
			
			return "";
		} catch ( final SQLException se ) {
			se.printStackTrace();
			if ( connection != null )
				try { connection.rollback(); } catch ( final SQLException se2 ) {}
			return "Report processing error!";
		}
		finally {
			if ( resultSet3 != null ) try { resultSet3.close(); } catch ( final SQLException se ) {}
			if ( statement3 != null ) try { statement3.close(); } catch ( final SQLException se ) {}
			if ( statement2 != null ) try { statement2.close(); } catch ( final SQLException se ) {}
			if ( resultSet  != null ) try { resultSet .close(); } catch ( final SQLException se ) {}
			if ( statement  != null ) try { statement .close(); } catch ( final SQLException se ) {}
			if ( connection != null ) try { connection.close(); } catch ( final SQLException se ) {}
		}
	}
	
	/**
	 * Sends back a message as plain text.
	 * @param message  message to be sent
	 * @param response response to be used
	 */
	private void sendBackPlainMessage( final String message, final HttpServletResponse response ) {
		response.setContentType( "text/plain" );
		try {
			final PrintWriter outputWriter = response.getWriter();
			outputWriter.println( message );
			outputWriter.flush();
			outputWriter.close();
		} catch ( final IOException ie ) {
			ie.printStackTrace();
		}
	}
	
	/**
	 * Sends back an error message indicating a bad request.
	 * @param response response to be used
	 */
	private void sendBackErrorMessage( final HttpServletResponse response ) {
		response.setContentType( "text/html" );
		try {
			final PrintWriter outputWriter = response.getWriter();
			outputWriter.println( "Bad request!" );
			outputWriter.flush();
			outputWriter.close();
		} catch ( final IOException ie ) {
			ie.printStackTrace();
		}
	}
	
	/**
	 * Configures the response for no caching.
	 * @param response response to be configured
	 */
	private void setNoCache( final HttpServletResponse response ) {
		response.setHeader( "Cache-Control", "no-cache" ); // For HTTP 1.1
		response.setHeader( "Pragma"       , "no-cache" ); // For HTTP 1.0
		response.setDateHeader ( "Expires", -0 );          // For proxies
	}
	
}
