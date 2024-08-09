#!/bin/bash

if [ $# -ne 2 ]; then
    echo "Usage: $0 <number_of_files> <input_file>"
    exit 1
fi


num_files=$1
input_file=$2
output_folder="splited_tests"

if [ ! -f "$input_file" ]; then
    echo "Error: Input file '$input_file' not found."
    exit 1
fi

mkdir -p "$output_folder"

total_lines=$(( $(wc -l < "$input_file") + 1 ))
lines_per_file=$(( total_lines / num_files ))
remainder=$(( total_lines % num_files ))


split --lines="$lines_per_file" --numeric-suffixes=1 --additional-suffix=.txt "$input_file" "$output_folder/part"


if [ $remainder -gt 0 ]; then
    tail -n "$remainder" "$input_file" >> "$output_folder/part$num_files.txt"
fi

echo "Splited tests into $num_files parts. Output folder is: $output_folder." 
