#!/bin/bash


ART_ID=$1
MAJOR=$2
MINOR=$3
USERNAME=$4
PASSWORD=$5
LIB=$6          # libs-release-local | libs-snapshots-local


ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}' artifactory)

if [ -z "$ip" ]; then
    echo "Error: Could not find IP address for Artifactory container."
    exit 1
fi


url="http://$ip:8081/artifactory/${LIB}/com/lidar/${ART_ID}/"

curl_output=$(curl -s -u "$USERNAME:$PASSWORD" "$url")

# Fetching the latest version of given package
latest_package=$(echo "$curl_output" | grep -oP '<a\b[^>]*>\K.*?(?=</a>)' | grep '^[0-9]' | sed 's/.$//' | sort -V)

if [ "$LIB" == "libs-release-local" ]; then
    echo "$latest_package" | grep "^${MAJOR}\.${MINOR}" | tail -n1 | cut -d "." -f 3
elif [ "$LIB" == "libs-snapshot-local" ]; then
    snapshot_url=$(echo "$latest_package" | tail -n1)
    result=$(curl -s -u "$USERNAME:$PASSWORD" "${url}${snapshot_url}/maven-metadata.xml" | grep -A 2 '<snapshotVersion>' | grep -A 2 '<extension>jar</extension>' | grep '<value>' | sed 's/<value>\(.*\)<\/value>/\1/g' | tr -d '[:space:]')
    echo "$latest_package/$result"
fi
