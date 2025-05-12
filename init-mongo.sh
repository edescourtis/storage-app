#!/bin/bash

# Wait for MongoDB to be ready
until mongosh --host localhost --eval "print(\"waited for connection\")"; do
  sleep 2
  echo "Waiting for MongoDB to start..."
done

# Initiate the replica set directly
mongosh --host localhost --eval 'rs.initiate({_id: "rs0", members: [{_id: 0, host: "localhost:27017"}]})' 