% Set up the serial connection (replace 'COM5' with your Arduino's COM port)
serialPort = 'COM10'; % Example for Windows (COM port), replace with your system's port
baudRate = 115200; % The baud rate used in the Arduino code

% Create the serial object with a longer timeout (e.g., 30 seconds)
s = serialport(serialPort, baudRate);

% Set terminator and timeout properties
s.Timeout = 30;  % Set timeout (in seconds)
configureTerminator(s, "LF");  % Set the line terminator (Line Feed)

% Open the serial port for communication (done automatically with serialport)
disp('Reading data from Arduino and saving to CSV...');

% Open a CSV file to save data
csvFile = 'filtered_ppg_data.csv';
fid = fopen(csvFile, 'w');
fprintf(fid, 'Red Signal, IR Signal\n'); % Write the CSV header

try
    while true
        % Read a line of data from the serial port
        data = readline(s);  % Use readline instead of fgetl

        % Skip empty or malformed lines
        if isempty(data)
            disp('Skipping empty line');
            continue; % Skip empty line
        end

        % Debugging: Display received data to check
        disp(['Received data: ', data]);

        % Split the received data (red, IR signal) and handle errors
        try
            dataParts = str2double(strsplit(data, ','));
            
            % If there are exactly 2 values (Red Signal and IR Signal), write them to CSV
            if numel(dataParts) == 2
                fprintf(fid, '%f, %f\n', dataParts(1), dataParts(2));
                disp(['Red: ', num2str(dataParts(1)), ', IR: ', num2str(dataParts(2))]); % Optional: print data to console
            else
                disp(['Skipping malformed line: ', data]); % Print message if the data is malformed
            end
        catch
            disp(['Error processing line: ', data]);
        end
    end
catch ME
    % In case of error, close the file and serial connection
    fclose(fid);
    clear s;  % Close serial connection properly
    rethrow(ME);
end