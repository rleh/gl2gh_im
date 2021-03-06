package de.harry_r.gl2gh_im;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Scanner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Migrate {
	private static String lab_URL;
	private static String lab_token;
	private static String hub_name;
	private static String hub_repo;
	private static String hub_token;
	private static String hub_final_url;
	private static final String charset = "UTF-8";

	private static int lab_project_id;

	private static boolean time_and_name;

	public static void main(String[] args) throws IOException, JSONException,
			ParseException {
		System.out.println("Version 0.4.0");
		if(args.length == 7) {
			lab_URL = args[0];
			lab_project_id = Integer.parseInt(args[1]);
			lab_token = args[2];
			hub_name = args[3];
			hub_repo = args[4];
			hub_token = args[5];
			time_and_name = args[6].equals("y");
			System.out.println("args parsed");
		}
		else {
			welcomeDialog();
		}
		JSONArray lab_issues = getLabIssues();
		if (lab_issues == null) {
			System.out.println("Error retrieving issues from Gitlab API");
			System.exit(1);
		}
		System.out.println("Number of Gitlab Issues: " + lab_issues.length());
		for (int i = lab_issues.length()-1; i >= 0; i--) {
			System.out.println("Issue #" + (lab_issues.length()-i));

			// Wait to not hit the rate limiting:
			try {
				Thread.sleep(7000); // milliseconds
			} catch(InterruptedException ex) {
				Thread.currentThread().interrupt();
			}

			JSONObject out;
			// createOutputJson() adds info ("migrated from Gitlab", author, creation date) to JSONObject if time_and_name == true 
			out = createOutputJson(lab_issues.getJSONObject(i), time_and_name);
			try {
				// create issue and save returned id of new issue
				int hub_issue_id = createIssue(out);
				// edit issue state
				editIssue(hub_issue_id, getState(lab_issues.getJSONObject(i)));
				// add comments to issue
				createHubCommentsOnIssue(hub_issue_id, getLabCommentsByIssueId(getIssueId(lab_issues.getJSONObject(i))));
			} catch(Exception e) {
				System.out.println("Error creating GitHub Issue: " + out.toString());
				e.printStackTrace();
				System.exit(1);
			}
		}
		System.out.println("Success");
	}

	private static void welcomeDialog() {
		// send welcome message
		System.out.println("Welcome at GitLab to GitHub issue migrating!");
		// read user inputs
		Scanner reader = new Scanner(System.in);
		System.out.println("Please enter your GitLab URL:");
		lab_URL = reader.next();
		System.out.println("Please enter your GitLab project id:");
		lab_project_id = reader.nextInt();
		System.out.println("Please enter your GitLab private token:");
		lab_token = reader.next();
		System.out.println("Please enter your GitHub name:");
		hub_name = reader.next();
		System.out.println("Please enter your GitHub repo:");
		hub_repo = reader.next();
		System.out.println("Please enter your GitHub API authentication token:");
		hub_token = reader.next();
		System.out
				.println("Do you want to write originally creator's name and timestamp into the description? If not, they will be lost! (y/n)");
		String input = "42";
		// read input until user does a correct input
		while (!input.equals("y") && !input.equals("n")) {
			input = reader.next();
		}
		switch (input) {
		case ("y"): {
			time_and_name = true;
			break;
		}
		case ("n"): {
			time_and_name = false;
			break;
		}
		}
		reader.close();
	}

	private static JSONObject performHttpPost(String url, JSONObject content)
			throws IOException, JSONException {
		// content type
		String type = "application/json";
		// create and open connection
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		// print URL for debugging
		System.out.println(connection);
		// set connection parameters
		connection.setRequestProperty("User-Agent",
				"gl2gh_im/0.3.8 Gitlab2Github Issue Migration Tool (OpenJDK Runtime Environment build 1.8.0_51-b16)");
		connection.setRequestProperty("Accept-Charset", charset);
		connection.setRequestProperty("Content-Type", type);
		connection.setRequestProperty("Content-Length",
				String.valueOf(content.toString().length()));
		connection.setDoOutput(true);
		connection.setRequestMethod("POST");
		// get and write output stream
		OutputStream os = connection.getOutputStream();
		os.write(content.toString().getBytes());
		// get and print http status codes
		int status = connection.getResponseCode();
		System.out.println(status);
		if(status >= 400) {
			InputStream is = connection.getErrorStream();
			// create a buffered reader and String builder
			BufferedReader rd = new BufferedReader(new InputStreamReader(is,
					charset));
			StringBuilder sb = new StringBuilder();
			// read from input stream and append to string builder
			int k;
			while ((k = rd.read()) >= 0) {
				sb.append((char) k);
			}
			System.out.println("Error: " + sb.toString());
		}

		// X-RateLimit headers
		String limit = connection.getHeaderField("X-RateLimit-Limit");
		if(limit != null){
			System.out.println("Limit: " + limit);
		}
		else {
			System.out.println("Limit: no");
		}
		String remaining = connection.getHeaderField("X-RateLimit-Remaining");
		if(remaining != null){
			System.out.println("Remaining: " + remaining);
		}
		else {
			System.out.println("Remaining: no");
		}
		String reset = connection.getHeaderField("X-RateLimit-Reset");
		if(reset != null){
			System.out.println("Reset: " + reset);
		}
		else {
			System.out.println("Reset: no");
		}


		// Get comments from lab an add to hub:
		// Read returned JSON from hub (for issue number)
		InputStream is = connection.getInputStream();
		// create a buffered reader and String builder
		BufferedReader rd = new BufferedReader(new InputStreamReader(is,
				charset));
		StringBuilder sb = new StringBuilder();
		// read from input stream and append to string builder
		int k;
		while ((k = rd.read()) >= 0) {
			sb.append((char) k);
		}
		return new JSONObject(sb.toString());
	}

	private static JSONArray getLabIssues() throws IOException, JSONException {
		// open connection
		String lab_final_url = lab_URL + "/api/v3/projects/" + lab_project_id
				+ "/issues" + "?" + "private_token=" + lab_token + "&per_page=100";
		HttpURLConnection conn = (HttpURLConnection) new URL(lab_final_url)
				.openConnection();
		if (conn.getResponseCode() == 200) {
			// open input stream
			try (InputStream is = conn.getInputStream()) {
				// create a buffered reader and String builder
				BufferedReader rd = new BufferedReader(new InputStreamReader(
						is, charset));
				StringBuilder sb = new StringBuilder();
				// read from input stream and append to string builder
				int i;
				while ((i = rd.read()) >= 0) {
					sb.append((char) i);
				}
				// build json object from String and return
				return new JSONArray(sb.toString());
			} catch (Exception e) {
				e.printStackTrace();
				return null;
			} finally {
				// close stream in case of exception
				conn.disconnect();
				System.out.println("Connection closed!");
			}
		}
		else {
			System.out.println("Connection to GitLab failed.");
			System.out.print("HTTP Status:");
			handleHttpStatusCode(conn.getResponseCode());
			return null;
		}
	}

	private static void handleHttpStatusCode(int status) {
		switch (status) {
		case (0): {
			System.out.println("Connection failed!");
			break;
		}
		case (200): {
			System.out.println("OK");
			break;
		}
		case (201): {
			System.out.println("Successfully created");
			break;
		}
		case (400): {
			System.out.println("Bad request!");
			break;
		}
		case (401): {
			System.out.println("Authentication failed!");
			break;
		}
		case (404): {
			System.out.println("Requested resource not found!");
			break;
		}
		default: {
			System.out.println("There was an unknown error!");
			break;
		}
		}
	}

	private static JSONObject createOutputJson(JSONObject in_object, boolean addinfo) throws JSONException, ParseException {
		// add relevant parameters output_json
			JSONObject out_object = new JSONObject();
			out_object.put("milestone", in_object.get("milestone"));
			out_object.put("title", in_object.get("title"));
			if(addinfo) {
				out_object.put("body", in_object.get("description")
				+ "\n\n_This issue was migrated from GitLab. Original author is "
				+ in_object.getJSONObject("author").getString("name")
				+ "._\n_It was originally created "
				+ reformatDate(in_object.getString("created_at")) + "._");
			}
			else {
				out_object.put("body", in_object.get("description"));
			}
			out_object.put("labels", in_object.get("labels"));
			return out_object;
	}

	private static int getIssueId(JSONObject in_object) throws JSONException {
		return in_object.getInt("id");
	}

	private static String reformatDate(String in_date) throws ParseException {
		// reformat date
		// Format example: 2015-04-26T22:42:04.897Z -> (removed T and Z)
		// 2015-04-26 22:42:04.897
		DateFormat json_format = new SimpleDateFormat(
				"yyyy-MM-dd HH:mm:ss.SSS ", Locale.ENGLISH);
		Date date = json_format.parse(in_date.replace('T', ' ').replace('Z',
				' '));
		DateFormat out_format = new SimpleDateFormat("yyyy-MM-dd HH:mm",
				Locale.ENGLISH);
		return out_format.format(date);
	}

	private static int createIssue(JSONObject out)
			throws IOException, JSONException {
		// create URL
		hub_final_url = "https://api.github.com/repos/" + hub_name + "/"
				+ hub_repo + "/issues" + "?access_token=" + hub_token;
		JSONObject created_issue = performHttpPost(hub_final_url, out);
		// return id ("number" key in returned JSON) of created issue
		return created_issue.getInt("number");
	}

	private static void createHubCommentsOnIssue(int id, JSONArray comments)
			throws JSONException, ParseException, IOException {
		for (int i = 0; i < comments.length(); i++) {
			String newcomment_string = comments.getJSONObject(i).getString(
					"body")
					+ "\n\n"
					+ "_This comment was migrated from GitLab. It was originally posted on "
					+ reformatDate(comments.getJSONObject(i).getString(
							"created_at"))
					+ " by "
					+ comments.getJSONObject(i).getJSONObject("author")
							.getString("name") + "._";
			JSONObject newcomment = new JSONObject();
			newcomment.put("body", newcomment_string);
			// create URL
			String comment_url = "https://api.github.com/repos/" + hub_name
					+ "/" + hub_repo + "/issues/" + id + "/comments"
					+ "?access_token=" + hub_token;
			performHttpPost(comment_url, newcomment);
		}
	}

	private static JSONArray getLabCommentsByIssueId(int issue_id)
			throws IOException, JSONException {
		// open input stream
		String lab_issues_url = lab_URL + "/api/v3/projects/" + lab_project_id
				+ "/issues/" + issue_id + "/notes" + "?" + "private_token="
				+ lab_token;
		try (InputStream is = new URL(lab_issues_url).openStream()) {
			// create a buffered reader and String builder
			BufferedReader rd = new BufferedReader(new InputStreamReader(is,
					charset));
			StringBuilder sb = new StringBuilder();
			// read from input stream and append to string builder
			int i;
			while ((i = rd.read()) >= 0) {
				sb.append((char) i);
			}
			// build json object from String and return
			return new JSONArray(sb.toString());
		} finally {
			// close stream in case of exception
			System.out.println("Connection closed!");
		}
	}

	private static String getState(JSONObject in_object) throws JSONException {
		// state for editing the issues
		return in_object.getString("state");
	}

	private static void editIssue(int id, String state) throws IOException, JSONException {
		// reverse to bring the issues in right order
		JSONObject edit_object = new JSONObject();
		edit_object.put("state", state);
		System.out.println(edit_object);
		// create URL, i+1 is the issue number
		hub_final_url = "https://api.github.com/repos/" + hub_name + "/"
				+ hub_repo + "/issues/" + id + "?access_token="
				+ hub_token;
		performHttpPost(hub_final_url, edit_object);
	}
}
