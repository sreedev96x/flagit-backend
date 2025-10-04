package com.flagit;

import static spark.Spark.*;
import com.google.gson.Gson;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.cloud.FirestoreClient;
import com.google.cloud.firestore.*;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutionException;
import com.google.api.core.ApiFuture;

public class Main {
    static Firestore db;
    static Gson gson = new Gson();

    public static void main(String[] args) throws Exception {
        InputStream serviceAccount = new FileInputStream("serviceAccountKey.json");
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build();
        FirebaseApp.initializeApp(options);
        db = FirestoreClient.getFirestore();
        secure("keystore.jks", "password", null, null);
        port(4567);
        before((req, res) -> {
            res.header("Access-Control-Allow-Origin", "*");
            res.header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
            res.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        });
        options("/*", (req, res) -> {
            res.status(200);
            return "OK";
        });

        get("/hello", (req, res) -> "FlagIt backend running");

        get("/item/:id", (req, res) -> {
            String itemId = req.params(":id");
            res.type("application/json");
            DocumentReference docRef = db.collection("items").document(itemId);
            ApiFuture<DocumentSnapshot> future = docRef.get();
            DocumentSnapshot document = future.get();
            if (document.exists()) {
                Map<String, Object> item = document.getData();
                item.put("id", document.getId());
                return gson.toJson(item);
            } else {
                res.status(404);
                return "{\"error\":\"Not Found\"}";
            }
        });

        get("/getItems", (req, res) -> {
            String idToken = req.headers("Authorization");
            if (idToken == null) halt(401, "Unauthorized");
            String cleanIdToken = idToken.startsWith("Bearer ") ? idToken.substring(7) : idToken;
            FirebaseAuth.getInstance().verifyIdToken(cleanIdToken);
            res.type("application/json");
            ApiFuture<QuerySnapshot> future = db.collection("items").orderBy("timestamp", Query.Direction.DESCENDING).get();
            List<QueryDocumentSnapshot> docs = future.get().getDocuments();
            List<Map<String, Object>> items = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                Map<String, Object> item = doc.getData();
                item.put("id", doc.getId());
                items.add(item);
            }
            return gson.toJson(items);
        });

        post("/addItem", (req, res) -> {
            res.type("application/json");
            Map<String, Object> item = gson.fromJson(req.body(), Map.class);
            item.put("timestamp", System.currentTimeMillis());
            item.put("voteCount", 1L);
            DocumentReference addedDocRef = db.collection("items").document();
            addedDocRef.set(item);
            String ownerId = (String) item.get("ownerId");
            if (ownerId != null) {
                Map<String, Object> initialVote = new HashMap<>();
                initialVote.put("direction", 1L);
                addedDocRef.collection("votes").document(ownerId).set(initialVote);
            }
            return "{\"status\":\"ok\", \"id\":\"" + addedDocRef.getId() + "\"}";
        });

        delete("/deleteItem/:id", (req, res) -> {
            String idToken = req.headers("Authorization");
            String itemId = req.params(":id");
            if (idToken == null || itemId == null) halt(401, "Unauthorized");
            String cleanIdToken = idToken.startsWith("Bearer ") ? idToken.substring(7) : idToken;
            FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(cleanIdToken);
            String uid = decodedToken.getUid();
            DocumentReference docRef = db.collection("items").document(itemId);
            ApiFuture<DocumentSnapshot> future = docRef.get();
            DocumentSnapshot document = future.get();
            if (document.exists()) {
                String ownerId = document.getString("ownerId");
                if (uid.equals(ownerId)) {
                    docRef.delete();
                    res.status(200);
                    return "{\"status\":\"ok\"}";
                } else {
                    halt(403, "Forbidden: User is not the owner.");
                    return "";
                }
            } else {
                halt(404, "Not Found.");
                return "";
            }
        });

        post("/item/:id/vote", (req, res) -> {
            String idToken = req.headers("Authorization");
            String itemId = req.params(":id");
            if (idToken == null || itemId == null) halt(401, "Unauthorized");
            Map<String, Object> body = gson.fromJson(req.body(), Map.class);
            long direction = ((Double) body.get("direction")).longValue();
            String cleanIdToken = idToken.startsWith("Bearer ") ? idToken.substring(7) : idToken;
            FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(cleanIdToken);
            String uid = decodedToken.getUid();
            DocumentReference itemRef = db.collection("items").document(itemId);
            DocumentReference voteRef = itemRef.collection("votes").document(uid);
            db.runTransaction(transaction -> {
                DocumentSnapshot voteSnapshot = transaction.get(voteRef).get();
                long oldDirection = 0;
                if (voteSnapshot.exists() && voteSnapshot.getLong("direction") != null) {
                    oldDirection = voteSnapshot.getLong("direction");
                }
                long voteChange = direction - oldDirection;
                if (voteChange != 0) {
                    transaction.update(itemRef, "voteCount", FieldValue.increment(voteChange));
                    if (direction == 0) {
                        transaction.delete(voteRef);
                    } else {
                        Map<String, Object> newVote = new HashMap<>();
                        newVote.put("direction", direction);
                        transaction.set(voteRef, newVote);
                    }
                }
                return null;
            }).get();
            res.status(200);
            return "{\"status\":\"ok\"}";
        });

        post("/item/:itemId/postComment", (req, res) -> {
            String idToken = req.headers("Authorization");
            if (idToken == null) halt(401, "Unauthorized");
            String cleanIdToken = idToken.startsWith("Bearer ") ? idToken.substring(7) : idToken;
            FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(cleanIdToken);

            Map<String, Object> body = gson.fromJson(req.body(), Map.class);
            String text = (String) body.get("text");
            String parentId = (String) body.get("parentId");
            String itemId = req.params(":itemId");

            Map<String, Object> newComment = new HashMap<>();
            newComment.put("text", text);
            newComment.put("timestamp", System.currentTimeMillis());
            newComment.put("ownerId", decodedToken.getUid());
            String email = decodedToken.getEmail();
            String username = (email != null && email.contains("@")) ? email.split("@")[0] : "user";
            newComment.put("username", username);

            CollectionReference parentCollection;
            if (parentId != null) {
                parentCollection = db.collection("items").document(itemId).collection("comments").document(parentId).collection("replies");
            } else {
                parentCollection = db.collection("items").document(itemId).collection("comments");
            }

            ApiFuture<DocumentReference> added = parentCollection.add(newComment);
            return "{\"status\":\"ok\", \"id\":\"" + added.get().getId() + "\"}";
        });

        get("/item/:id/comments", (req, res) -> {
            String itemId = req.params(":id");
            res.type("application/json");
            ApiFuture<QuerySnapshot> future = db.collection("items").document(itemId)
                    .collection("comments").orderBy("timestamp", Query.Direction.ASCENDING).get();
            List<Map<String, Object>> comments = new ArrayList<>();
            for (QueryDocumentSnapshot doc : future.get().getDocuments()) {
                Map<String, Object> comment = doc.getData();
                comment.put("id", doc.getId());
                comments.add(comment);
            }
            return gson.toJson(comments);
        });

        get("/item/:itemId/comment/:commentId/replies", (req, res) -> {
            String itemId = req.params(":itemId");
            String commentId = req.params(":commentId");
            res.type("application/json");
            ApiFuture<QuerySnapshot> future = db.collection("items").document(itemId)
                    .collection("comments").document(commentId)
                    .collection("replies").orderBy("timestamp", Query.Direction.ASCENDING).get();
            List<Map<String, Object>> replies = new ArrayList<>();
            for (QueryDocumentSnapshot doc : future.get().getDocuments()) {
                Map<String, Object> reply = doc.getData();
                reply.put("id", doc.getId());
                replies.add(reply);
            }
            return gson.toJson(replies);
        });
    }
}