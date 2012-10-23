#
# Copyright 2010 The Apache Software Foundation
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

module Shell
  module Commands
    class GroupOfServer < Command
      def help
        return <<-EOF
Get the group name the given server is a member of.

  hbase> group_of_server 'server1:port1'
EOF
      end

      def command(server)
        now = Time.now
        groupName = group_admin.getGroupOfServer(server).getName
        formatter.row([ groupName ])
        formatter.footer(now,1)
      end
    end
  end
end
