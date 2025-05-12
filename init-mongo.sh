#!/bin/bash

# Wait for MongoDB to be available
until mongosh --host localhost --eval "print(\"waited for connection\")"; do
  sleep 2
  echo "Waiting for MongoDB to start..."
done

# Initiate the replica set
mongosh --host localhost < /init-mongo.js 